package com.doh.ahbench.bench;

import com.doh.ahbench.bench.BenchExceptions.InvalidCredentialsException;
import com.doh.ahbench.bench.BenchExceptions.UserNotFoundException;
import com.doh.ahbench.common.ConfigId;
import com.doh.ahbench.crypto.Aes256CipherService;
import com.doh.ahbench.repo.BenchRowRepository;
import com.doh.ahbench.repo.Rows.FullScanRow;
import com.doh.ahbench.security.PasswordService;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 구성 A: 블로그가 쓰던 "기존 방식" 기준선.
 * 테이블에는 AES256 암호문만 있다. 이메일로 조회하려면 전체 행을 가져와 하나씩
 * 복호화하고 평문과 비교할 수밖에 없다 (계획서 2장 "비교 기준").
 *
 * 비용은 n × (복호화 1회 비용)이고 대부분 jasypt 키 유도 비용이 지배적일 것으로
 * 계획서는 예상한다 (H2). 큰 규모에서는 끝까지 돌리기 어려우므로(계획서 4장) 1만/10만
 * 건까지만 쓰고, 그 이상은 타임아웃 지점 확인용으로만 돌린다.
 */
public class FullScanConfigHandler implements BenchmarkOperations {

    private final ConfigId configId;
    private final String userTable;
    private final BenchRowRepository repo;
    private final Aes256CipherService cipher;
    private final PasswordService passwordService;
    private final DecryptTimingLogger timingLogger;

    public FullScanConfigHandler(
            ConfigId configId,
            String userTable,
            BenchRowRepository repo,
            Aes256CipherService cipher,
            PasswordService passwordService,
            DecryptTimingLogger timingLogger) {
        this.configId = configId;
        this.userTable = userTable;
        this.repo = repo;
        this.cipher = cipher;
        this.passwordService = passwordService;
        this.timingLogger = timingLogger;
    }

    @Override
    public LoginResult login(String email, String password) {
        AtomicLong decryptNanos = new AtomicLong(0);
        Optional<FullScanRow> found = findByFullScan(email, decryptNanos);
        timingLogger.record(configId, "login", decryptNanos.get());

        if (found.isEmpty()) {
            return LoginResult.notFound();
        }
        FullScanRow row = found.get();
        if (!passwordService.matches(password, row.passwordHash())) {
            return LoginResult.wrongPassword(decryptNanos.get());
        }
        return LoginResult.ok(row.id(), email, decryptNanos.get());
    }

    @Override
    public long signup(String email, String rawPassword) {
        String emailAes256 = cipher.encrypt(email);
        String passwordHash = passwordService.hash(rawPassword);
        return repo.insertFullScanUser(userTable, emailAes256, passwordHash);
    }

    @Override
    public void withdraw(String email, String confirmPassword) {
        AtomicLong decryptNanos = new AtomicLong(0);
        FullScanRow row = findByFullScan(email, decryptNanos)
                .orElseThrow(() -> new UserNotFoundException(email));
        if (!passwordService.matches(confirmPassword, row.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        repo.deleteFullScanUser(userTable, row.id());
    }

    /**
     * 전체 조회 후 행마다 복호화·비교. 블로그의 기존 방식을 그대로 구현한 것.
     * 일치하는 행을 찾은 뒤에는 나머지 행의 복호화(지배적 비용)는 건너뛰지만, 행 가져오기
     * 자체는 커서가 끝까지 흐른다 — 평균적으로는 복호화 n/2회 비용. "존재하지 않는
     * 이메일"(S5) 조회는 항상 복호화 n회 전체 비용을 치른다.
     */
    private Optional<FullScanRow> findByFullScan(String targetEmail, AtomicLong decryptNanosOut) {
        FoundHolder holder = new FoundHolder();
        // 일치하는 행을 찾아도 커서(행 가져오기) 자체는 끝까지 흘려보낸다. 블로그의 기존 방식은
        // repository.findAll() 뒤에 .stream().filter(...)로 거르는 구조라, findAll()이 이미
        // 테이블 전체를 메모리로 올려버린 뒤에야 필터링이 시작된다 — 즉 I/O(행 전송) 비용은
        // 매칭 위치와 무관하게 항상 O(n)이고, 오직 CPU 비용(복호화)만 Stream의 findFirst()
        // 단축 평가로 아낄 수 있다. 여기서는 1000만 건 규모에서 findAll()을 그대로 쓰면 OOM이
        // 나므로 스트리밍 커서로 바꿨을 뿐, 그 비용 모델(I/O는 항상 전체, 복호화만 조기 중단)은
        // 그대로 유지해 블로그 방식과 같은 조건으로 비교되게 한다. 커서를 여기서도 조기에 끊어
        // I/O까지 줄이면 "findAll() 기반 기존 방식"이 아니라 더 최적화된 다른 구현을 재는 셈이
        // 되어 비교 기준이 달라진다.
        repo.streamFullScan(userTable, row -> {
            if (holder.found != null) {
                return;
            }
            long start = System.nanoTime();
            String decrypted = cipher.decrypt(row.emailAes256());
            decryptNanosOut.addAndGet(System.nanoTime() - start);
            if (decrypted.equals(targetEmail)) {
                holder.found = row;
            }
        });
        return Optional.ofNullable(holder.found);
    }

    private static final class FoundHolder {
        FullScanRow found;
    }
}
