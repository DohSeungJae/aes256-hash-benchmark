package com.doh.ahbench.generator;

import com.doh.ahbench.crypto.Aes256CipherService;
import com.doh.ahbench.crypto.HmacSha256HashService;
import com.doh.ahbench.crypto.Sha256HashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 계획서 6장 "데이터 설계와 생성" 구현.
 *
 * - seq 1부터 순서대로 이메일을 규칙적으로 만들고, 각 이메일의 SHA-256 / HMAC-SHA256 해시,
 *   AES256 암호문을 같이 CSV에 기록한다. 테스트에서는 seq를 무작위로 뽑아 캐시 편향을 피한다
 *   (CSV 자체는 규칙적 순서로 저장해도 무방 — 무작위 추출은 k6 쪽 책임).
 * - 100만 건(기본 chunkSize)마다 파일을 나눠 저장해 중간 실패해도 이어서 할 수 있게 한다.
 * - 암호화/해시 계산(CPU-bound)만 스레드 풀로 병렬화하고, 파일 쓰기는 청크당 한 스레드가
 *   순서대로 수행해 CSV 행 순서를 보존한다.
 * - Aes256CipherService(PooledPBEStringEncryptor 기반), Sha256HashService, HmacSha256HashService는
 *   모두 스레드 안전하게 구현돼 있어 여러 스레드에서 안전하게 재사용할 수 있다.
 *
 * 실행: java -jar ahbench.jar --spring.profiles.active=generate
 */
@Component
@Profile("generate")
public class DataGeneratorRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataGeneratorRunner.class);
    private static final String CSV_HEADER = "seq,email,email_hash_sha256,email_hash_hmac,email_aes256";

    private final GeneratorProperties props;
    private final Aes256CipherService cipher;
    private final Sha256HashService sha256;
    private final HmacSha256HashService hmac;

    public DataGeneratorRunner(
            GeneratorProperties props,
            Aes256CipherService cipher,
            Sha256HashService sha256,
            HmacSha256HashService hmac) {
        this.props = props;
        this.cipher = cipher;
        this.sha256 = sha256;
        this.hmac = hmac;
    }

    @Override
    public void run(String... args) throws Exception {
        long total = props.getTotalRows();
        long chunkSize = props.getChunkSize();
        long startSeq = props.getStartSeq();
        int threads = Math.max(1, props.getThreadCount());

        Path outDir = Path.of(props.getOutputDir());
        Files.createDirectories(outDir);

        log.info("데이터 생성 시작: total={}, chunkSize={}, threads={}, startSeq={}, outputDir={}",
                total, chunkSize, threads, startSeq, outDir.toAbsolutePath());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            long remaining = total - (startSeq - 1);
            long chunkStart = startSeq;
            int fileIndex = (int) ((startSeq - 1) / chunkSize);

            while (remaining > 0) {
                long thisChunkSize = Math.min(chunkSize, remaining);
                long chunkEndExclusive = chunkStart + thisChunkSize;

                Path chunkFile = outDir.resolve(String.format("users_%03d.csv", fileIndex));
                long t0 = System.nanoTime();
                writeChunk(pool, threads, chunkStart, chunkEndExclusive, chunkFile);
                double seconds = (System.nanoTime() - t0) / 1_000_000_000.0;
                log.info("청크 완료: file={}, rows=[{}, {}), 소요={}s", chunkFile, chunkStart,
                        chunkEndExclusive, String.format("%.1f", seconds));

                chunkStart = chunkEndExclusive;
                remaining -= thisChunkSize;
                fileIndex++;
            }
        } finally {
            pool.shutdown();
        }

        log.info("데이터 생성 완료. 생성기 검증 절차(계획서 6장): 100건으로 복호화·해시 일치를 먼저 확인할 것.");
    }

    /** [fromSeqInclusive, toSeqExclusive) 범위를 threads개로 나눠 병렬 계산 후, 순서대로 파일에 쓴다. */
    private void writeChunk(ExecutorService pool, int threads, long fromSeqInclusive, long toSeqExclusive,
            Path outFile) throws IOException {
        long rangeSize = toSeqExclusive - fromSeqInclusive;
        long perTask = Math.max(1, (rangeSize + threads - 1) / threads);

        List<Future<List<String>>> futures = new ArrayList<>();
        for (long subStart = fromSeqInclusive; subStart < toSeqExclusive; subStart += perTask) {
            long subEnd = Math.min(subStart + perTask, toSeqExclusive);
            futures.add(pool.submit(generateRange(subStart, subEnd)));
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            writer.write(CSV_HEADER);
            writer.newLine();
            for (Future<List<String>> future : futures) {
                for (String line : future.get()) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("데이터 생성이 중단되었습니다.", e);
        } catch (ExecutionException e) {
            throw new IOException("데이터 생성 중 오류가 발생했습니다.", e.getCause());
        }
    }

    private Callable<List<String>> generateRange(long fromSeqInclusive, long toSeqExclusive) {
        return () -> {
            List<String> lines = new ArrayList<>((int) (toSeqExclusive - fromSeqInclusive));
            for (long seq = fromSeqInclusive; seq < toSeqExclusive; seq++) {
                String email = "user" + seq + "@" + props.getEmailDomain();
                String hashSha256 = sha256.hash(email);
                String hashHmac = hmac.hash(email);
                String aes256 = cipher.encrypt(email);
                lines.add(seq + "," + email + "," + hashSha256 + "," + hashHmac + "," + aes256);
            }
            return lines;
        };
    }
}
