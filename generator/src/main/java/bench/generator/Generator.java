package bench.generator;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.jasypt.salt.RandomSaltGenerator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * 회원 테스트 데이터(CSV) 생성기.
 *
 * <p>CSV 컬럼: seq, email, email_hash, email_aes256
 *
 * <pre>
 * 생성: ENCRYPTION_KEY=... java ... Generator --total 100 --chunk-size 100 --out ./out-test
 * 검증: ENCRYPTION_KEY=... java ... Generator --verify ./out-test/members_chunk_001.csv --limit 100
 * </pre>
 *
 * <p>환경 변수: ENCRYPTION_KEY(필수), HASH_MODE(sha256 | hmac, 기본 sha256), HASH_KEY(hmac일 때 필수)
 */
public final class Generator {

    private static final String HEADER = "seq,email,email_hash,email_aes256";
    private static final String DIGITS36 = "0123456789abcdefghijklmnopqrstuvwxyz";

    // 이메일 끝의 5자리 base36 토큰: seq를 일대일로 섞어서 만들기 때문에 이메일 고유성이 보장된다.
    // (MUL이 36^5와 서로소이므로 0 ~ 36^5-1 범위에서 중복 없이 대응된다. 최대 약 6,046만 건)
    private static final long TOKEN_SPACE = 60_466_176L; // 36^5
    private static final long TOKEN_MUL = 48_271L;
    private static final long TOKEN_ADD = 12_345_678L;

    private static final String[] FIRST = {
            "minjun", "seojun", "doyun", "haeun", "seoyeon", "jiwoo", "yuna", "jihun", "sooyoung", "hyunwoo",
            "jiho", "eunji", "taeyang", "narae", "sunghyun", "jaewon", "dahye", "yerin", "hyejin", "junho",
            "minseo", "chaewon", "siwoo", "jiyoon", "woojin", "harin", "donghyun", "somin", "kyungmin", "yujin",
            "jongho", "sora", "minho", "hana", "gunwoo", "bora", "seungmin", "jisoo", "taehyun", "nayeon",
            "john", "emma", "david", "sarah", "michael", "anna", "chris", "lisa", "kevin", "jenny",
            "alex", "ryan", "grace", "daniel", "sophie"};
    private static final String[] LAST = {
            "kim", "lee", "park", "choi", "jung", "kang", "cho", "yoon", "jang", "lim",
            "han", "oh", "seo", "shin", "kwon", "hwang", "ahn", "song", "yoo", "hong",
            "moon", "bae", "baek", "nam", "ko", "smith", "brown", "miller", "wilson", "taylor"};
    private static final String[] NICK = {
            "sunny", "bluesky", "coffee", "dreamer", "night", "moon", "happy", "star", "coder", "dev",
            "tiger", "panda", "cloud", "green", "lucky"};
    private static final String[] CO_WORD = {
            "han", "dae", "mir", "sun", "new", "star", "blue", "green", "nova", "geo", "ever", "top"};
    private static final String[] CO_KIND = {"soft", "corp", "tech", "bank", "trade", "labs", "systems"};
    private static final String[] CO_TLD = {".co.kr", ".com", ".kr"};

    private final long seed;
    private final String hashMode;
    private final ThreadLocal<StandardPBEStringEncryptor> encryptor;
    private final ThreadLocal<Mac> mac;
    private final ForkJoinPool pool;

    // 진행 상황 (행 단위로 실시간 갱신)
    private long totalToDo;
    private final AtomicLong doneRows = new AtomicLong();
    private volatile int curChunk;
    private volatile int chunkTotal;
    private long startNanos;
    private long logEvery; // 0이면 1초마다 한 줄 갱신, N이면 N행마다 한 줄씩 출력

    private Generator(String encKey, String hashMode, String hashKey, long seed, int threads) {
        this.seed = seed;
        this.hashMode = hashMode;
        // 스레드마다 암호화 객체를 따로 둔다.
        // 설정은 서버 Aes256CipherService의 기본값(bench.encryption.algorithm=sha256)과 같다.
        // (AES256TextEncryptor는 PBEWithHMACSHA512AndAES_256이라 서버 기본 설정과 호환되지 않는다)
        this.encryptor = ThreadLocal.withInitial(() -> {
            StandardPBEStringEncryptor e = new StandardPBEStringEncryptor();
            e.setAlgorithm("PBEWITHHMACSHA256ANDAES_256");
            e.setPassword(encKey);
            e.setIvGenerator(new RandomIvGenerator());
            e.setSaltGenerator(new RandomSaltGenerator());
            e.setStringOutputType("base64");
            return e;
        });
        if ("hmac".equals(hashMode)) {
            byte[] keyBytes = hashKey.getBytes(StandardCharsets.UTF_8);
            this.mac = ThreadLocal.withInitial(() -> {
                try {
                    Mac m = Mac.getInstance("HmacSHA256");
                    m.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
                    return m;
                } catch (GeneralSecurityException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        } else {
            this.mac = null;
        }
        this.pool = new ForkJoinPool(threads);
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        // 터미널 기본 인코딩과 상관없이 한글이 깨지지 않게 출력을 UTF-8로 고정한다.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("오류: " + e.getMessage());
            exit = 2;
        } catch (Exception e) {
            e.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private static int run(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);

        String encKey = System.getenv("ENCRYPTION_KEY");
        if (encKey == null || encKey.isBlank()) {
            throw new IllegalArgumentException("환경 변수 ENCRYPTION_KEY가 필요합니다. (서버와 같은 값)");
        }
        String hashMode = Objects.requireNonNullElse(System.getenv("HASH_MODE"), "sha256").toLowerCase(Locale.ROOT);
        if (!hashMode.equals("sha256") && !hashMode.equals("hmac")) {
            throw new IllegalArgumentException("HASH_MODE는 sha256 또는 hmac이어야 합니다: " + hashMode);
        }
        String hashKey = System.getenv("HASH_KEY");
        if (hashMode.equals("hmac") && (hashKey == null || hashKey.isBlank())) {
            throw new IllegalArgumentException("HASH_MODE=hmac이면 환경 변수 HASH_KEY가 필요합니다.");
        }

        long seed = Long.parseLong(a.getOrDefault("seed", "20261110"));
        int threads = Integer.parseInt(a.getOrDefault("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));

        Generator g = new Generator(encKey, hashMode, hashKey, seed, threads);
        try {
            if (a.containsKey("verify")) {
                long limit = Long.parseLong(a.getOrDefault("limit", "1000"));
                return g.verify(Paths.get(a.get("verify")), limit);
            }

            long total = Long.parseLong(a.getOrDefault("total", "100"));
            int chunkSize = Integer.parseInt(a.getOrDefault("chunk-size", "1000000"));
            int batch = Integer.parseInt(a.getOrDefault("batch", "10000"));
            g.logEvery = Long.parseLong(a.getOrDefault("log-every", "0"));
            Path out = Paths.get(a.getOrDefault("out", "./out"));
            if (total < 1 || chunkSize < 1 || batch < 1 || threads < 1) {
                throw new IllegalArgumentException("total, chunk-size, batch, threads는 1 이상이어야 합니다.");
            }
            if (total > TOKEN_SPACE) {
                throw new IllegalArgumentException("한 번에 만들 수 있는 최대 건수는 " + TOKEN_SPACE + "건입니다.");
            }
            g.generateAll(out, total, chunkSize, batch, threads);
            return 0;
        } finally {
            g.pool.shutdown();
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--")) {
                throw new IllegalArgumentException("알 수 없는 인자: " + key);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("값이 없습니다: " + key);
            }
            m.put(key.substring(2), args[++i]);
        }
        return m;
    }

    // -------------------------------------------------------------- generate

    private void generateAll(Path out, long total, int chunkSize, int batch, int threads) throws Exception {
        Files.createDirectories(out);
        checkMeta(out, chunkSize);

        int chunkCount = (int) ((total + chunkSize - 1) / chunkSize);
        totalToDo = 0;
        for (int c = 1; c <= chunkCount; c++) {
            if (!Files.exists(chunkPath(out, c))) {
                totalToDo += chunkTo(c, chunkSize, total) - chunkFrom(c, chunkSize) + 1;
            }
        }

        System.out.printf("총 %,d건 / 청크 %d개(청크당 %,d건) / 스레드 %d / 해시 %s / 출력 %s%n",
                total, chunkCount, chunkSize, threads, hashMode, out.toAbsolutePath());
        if (totalToDo == 0) {
            System.out.println("모든 청크 파일이 이미 있습니다. 할 일이 없습니다.");
            return;
        }

        startNanos = System.nanoTime();
        doneRows.set(0);
        chunkTotal = chunkCount;
        ScheduledExecutorService ticker = null;
        if (logEvery <= 0) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "progress");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleAtFixedRate(() -> printProgress(false), 1, 1, TimeUnit.SECONDS);
        }
        try {

        for (int c = 1; c <= chunkCount; c++) {
            Path target = chunkPath(out, c);
            long from = chunkFrom(c, chunkSize);
            long to = chunkTo(c, chunkSize, total);
            if (Files.exists(target)) {
                System.out.printf("[청크 %d/%d] 이미 있음, 건너뜀: %s%n", c, chunkCount, target.getFileName());
                continue;
            }
            curChunk = c;

            // 임시 파일에 쓰고 끝나면 이름을 바꾼다. (중간에 끊겨도 쓰다 만 파일이 완성본으로 남지 않는다)
            Path tmp = out.resolve(target.getFileName() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(HEADER);
                w.write('\n');
                for (long b = from; b <= to; b += batch) {
                    long e = Math.min(to, b + batch - 1);
                    String[] lines = compute(b, e);
                    for (String ln : lines) {
                        w.write(ln);
                        w.write('\n');
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        }
        } finally {
            if (ticker != null) {
                ticker.shutdownNow();
            }
        }

        printProgress(true);
        double sec = (System.nanoTime() - startNanos) / 1e9;
        System.out.printf("완료: 이번 실행 %,d건, 경과 %s%n", doneRows.get(), hms((long) sec));
    }

    private String[] compute(long from, long to) throws Exception {
        return pool.submit(() -> LongStream.rangeClosed(from, to)
                .parallel()
                .mapToObj(this::line)
                .toArray(String[]::new)).get();
    }

    private String line(long seq) {
        String email = email(seed, seq);
        String cipher = encryptor.get().encrypt(email).replaceAll("\\s", ""); // 줄바꿈이 섞여도 CSV가 깨지지 않게
        String row = seq + "," + email + "," + hash(email) + "," + cipher;
        long n = doneRows.incrementAndGet();
        if (logEvery > 0 && n % logEvery == 0) {
            System.out.printf("%,d/%,d%n", n, totalToDo);
        }
        return row;
    }

    String hash(String email) {
        byte[] in = email.getBytes(StandardCharsets.UTF_8);
        byte[] out;
        if (mac != null) {
            out = mac.get().doFinal(in);
        } else {
            try {
                out = MessageDigest.getInstance("SHA-256").digest(in);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return Base64.getEncoder().encodeToString(out);
    }

    private void checkMeta(Path out, int chunkSize) throws IOException {
        // 이어서 실행할 때 설정이 달라지면 서로 다른 데이터가 섞이므로 막는다.
        // (키 값은 기록하지 않는다. 키가 바뀌었는지는 --verify로 확인한다.)
        Path meta = out.resolve("generation.meta");
        String now = "seed=" + seed + "\nchunkSize=" + chunkSize + "\nhashMode=" + hashMode + "\n";
        if (Files.exists(meta)) {
            String old = Files.readString(meta, StandardCharsets.UTF_8);
            if (!old.equals(now)) {
                throw new IllegalStateException("이 폴더(" + out + ")는 다른 설정으로 만들어졌습니다.\n"
                        + "[기존]\n" + old + "[현재]\n" + now
                        + "--out 폴더를 바꾸거나 기존 폴더를 비운 뒤 다시 실행하세요.");
            }
        } else {
            Files.writeString(meta, now, StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- email

    static String email(long seed, long seq) {
        SplittableRandom r = new SplittableRandom(seed ^ (seq * 0x9E3779B97F4A7C15L));
        String prefix = prefix(r);
        int q = r.nextInt(10);
        String sep = q < 5 ? "" : q < 7 ? "." : q < 9 ? "_" : "-";
        return prefix + sep + token(seq) + "@" + domain(r);
    }

    private static String prefix(SplittableRandom r) {
        int p = r.nextInt(100);
        String f = pick(FIRST, r);
        String l = pick(LAST, r);
        if (p < 22) return f + "." + l;
        if (p < 36) return f + l;
        if (p < 44) return f + "_" + l;
        if (p < 52) return l + f;
        if (p < 66) return f + (r.nextInt(90) + 10);
        if (p < 72) return f.charAt(0) + l;
        if (p < 78) return f + "-" + l;
        if (p < 86) return pick(NICK, r) + (r.nextInt(3) == 0 ? "" : String.valueOf(r.nextInt(100)));
        if (p < 92) return f + "." + pick(FIRST, r) + "." + l;
        if (p < 96) return f + l + (1970 + r.nextInt(40));
        return pick(NICK, r) + "_" + f + "_" + l;
    }

    private static String domain(SplittableRandom r) {
        int p = r.nextInt(100);
        if (p < 40) return "gmail.com";
        if (p < 65) return "naver.com";
        if (p < 70) return "daum.net";
        if (p < 75) return "kakao.com";
        if (p < 78) return "nate.com";
        if (p < 80) return "hanmail.net";
        if (p < 84) return "outlook.com";
        if (p < 86) return "hotmail.com";
        if (p < 88) return "yahoo.com";
        if (p < 91) return "icloud.com";
        return pick(CO_WORD, r) + pick(CO_KIND, r) + pick(CO_TLD, r); // 가상의 회사 도메인
    }

    private static String token(long seq) {
        long v = (seq * TOKEN_MUL + TOKEN_ADD) % TOKEN_SPACE;
        char[] c = new char[5];
        for (int i = 4; i >= 0; i--) {
            c[i] = DIGITS36.charAt((int) (v % 36));
            v /= 36;
        }
        return new String(c);
    }

    private static String pick(String[] arr, SplittableRandom r) {
        return arr[r.nextInt(arr.length)];
    }

    // ---------------------------------------------------------------- verify

    private int verify(Path file, long limit) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (!HEADER.equals(header)) {
                throw new IllegalStateException("헤더가 다릅니다: " + header);
            }
            String ln;
            while ((ln = r.readLine()) != null && (limit <= 0 || lines.size() < limit)) {
                lines.add(ln);
            }
        }

        List<String> problems = pool.submit(() -> lines.parallelStream()
                .map(this::check)
                .filter(Objects::nonNull)
                .toList()).get();

        Set<String> emails = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        Map<String, Integer> domains = new HashMap<>();
        long minLen = Long.MAX_VALUE, maxLen = 0, sumLen = 0;
        long minCt = Long.MAX_VALUE, maxCt = 0, sumCt = 0;
        for (String ln : lines) {
            String[] p = ln.split(",", -1);
            if (p.length != 4) continue;
            emails.add(p[1]);
            hashes.add(p[2]);
            domains.merge(p[1].substring(p[1].indexOf('@') + 1), 1, Integer::sum);
            minLen = Math.min(minLen, p[1].length());
            maxLen = Math.max(maxLen, p[1].length());
            sumLen += p[1].length();
            minCt = Math.min(minCt, p[3].length());
            maxCt = Math.max(maxCt, p[3].length());
            sumCt += p[3].length();
        }
        int n = lines.size();
        int dupEmail = n - emails.size();
        int dupHash = n - hashes.size();

        System.out.printf("검증 파일: %s%n", file);
        System.out.printf("검사한 행: %,d (해시 방식: %s)%n", n, hashMode);
        System.out.printf("문제: %d건 / 이메일 중복: %d / 해시 중복: %d%n", problems.size(), dupEmail, dupHash);
        problems.stream().limit(10).forEach(s -> System.out.println("  - " + s));
        if (n > 0) {
            System.out.printf("이메일 길이: 최소 %d / 평균 %.1f / 최대 %d%n", minLen, (double) sumLen / n, maxLen);
            System.out.printf("암호문 길이(문자): 최소 %d / 평균 %.1f / 최대 %d%n", minCt, (double) sumCt / n, maxCt);
            System.out.println("도메인 상위 5:");
            domains.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(5)
                    .forEach(e -> System.out.printf("  %-16s %,d (%.1f%%)%n",
                            e.getKey(), e.getValue(), 100.0 * e.getValue() / n));
            System.out.printf("서로 다른 도메인: %d개%n", new TreeMap<>(domains).size());
            System.out.println("앞 3행 이메일: " + String.join(" | ",
                    lines.stream().limit(3).map(s -> s.split(",", -1)[1]).toList()));
        }
        boolean ok = problems.isEmpty() && dupEmail == 0 && dupHash == 0 && n > 0;
        System.out.println(ok ? "결과: 모두 통과" : "결과: 실패");
        return ok ? 0 : 1;
    }

    /** 한 행을 검사하고 문제가 있으면 설명을, 없으면 null을 돌려준다. */
    private String check(String ln) {
        String[] p = ln.split(",", -1);
        if (p.length != 4) {
            return "필드 수가 4개가 아님: " + abbreviate(ln);
        }
        String seq = p[0], email = p[1], hash = p[2], cipher = p[3];
        try {
            String plain = encryptor.get().decrypt(cipher);
            if (!plain.equals(email)) {
                return "seq=" + seq + " 복호화 결과가 이메일과 다름";
            }
        } catch (RuntimeException e) {
            return "seq=" + seq + " 복호화 실패 (키 또는 jasypt 버전 확인): " + e.getMessage();
        }
        if (!hash(email).equals(hash)) {
            return "seq=" + seq + " 해시 불일치 (HASH_MODE/HASH_KEY 확인)";
        }
        if (!email(seed, Long.parseLong(seq)).equals(email)) {
            return "seq=" + seq + " 이메일이 seed로 재생성한 값과 다름 (--seed 확인)";
        }
        return null;
    }

    // ----------------------------------------------------------------- misc

    private static Path chunkPath(Path out, int c) {
        return out.resolve(String.format("members_chunk_%03d.csv", c));
    }

    private static long chunkFrom(int c, int chunkSize) {
        return (c - 1L) * chunkSize + 1;
    }

    private static long chunkTo(int c, int chunkSize, long total) {
        return Math.min(total, (long) c * chunkSize);
    }

    /** 전체 진행 상황을 한 줄로 출력한다. 터미널에서는 같은 줄을 덮어쓴다. */
    private void printProgress(boolean last) {
        long done = doneRows.get();
        double sec = Math.max(1e-9, (System.nanoTime() - startNanos) / 1e9);
        double rate = done / sec;
        long eta = rate > 0 ? (long) ((totalToDo - done) / rate) : 0;
        String msg = String.format("[청크 %d/%d] %,d/%,d (%.1f%%) | %,.0f행/초 | 경과 %s | 남은 시간 약 %s",
                curChunk, chunkTotal, done, totalToDo, 100.0 * done / totalToDo, rate, hms((long) sec), hms(eta));
        if (System.console() != null && !last) {
            System.out.print("\r" + msg + "   ");
            System.out.flush();
        } else {
            if (System.console() != null) {
                System.out.print("\r");
            }
            System.out.println(msg);
        }
    }

    private static String hms(long sec) {
        return String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}