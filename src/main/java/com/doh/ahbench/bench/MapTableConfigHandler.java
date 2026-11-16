package com.doh.ahbench.bench;

import com.doh.ahbench.bench.BenchExceptions.DuplicateEmailException;
import com.doh.ahbench.bench.BenchExceptions.InvalidCredentialsException;
import com.doh.ahbench.bench.BenchExceptions.UserNotFoundException;
import com.doh.ahbench.common.ConfigId;
import com.doh.ahbench.crypto.Aes256CipherService;
import com.doh.ahbench.crypto.HashService;
import com.doh.ahbench.repo.BenchRowRepository;
import com.doh.ahbench.repo.Rows.MapRow;
import com.doh.ahbench.repo.Rows.MapShapeUserRow;
import com.doh.ahbench.security.PasswordService;

/**
 * 구성 B/C/D (+ C_HMAC/D_HMAC): 블로그 원안 구조.
 * user 테이블에는 해시만, map 테이블이 해시↔AES256 암호문 쌍을 보관한다.
 * 조회는 해시로 두 테이블을 각각 찾는다 — user는 인증(비밀번호 검증)용, map은 복호화용.
 *
 * B/C/D의 차이는 이 클래스가 아니라 Flyway DDL의 인덱스 유무(없음/B-tree/hash)에만 있다.
 * 같은 코드로 세 구성을 동시에 돌려 "인덱스 종류"만 변수로 남긴다 (계획서 9장 공정성 조건).
 */
public class MapTableConfigHandler implements BenchmarkOperations {

    private final ConfigId configId;
    private final String userTable;
    private final String mapTable;
    private final BenchRowRepository repo;
    private final Aes256CipherService cipher;
    private final HashService hashService;
    private final PasswordService passwordService;
    private final DecryptTimingLogger timingLogger;

    public MapTableConfigHandler(
            ConfigId configId,
            String userTable,
            String mapTable,
            BenchRowRepository repo,
            Aes256CipherService cipher,
            HashService hashService,
            PasswordService passwordService,
            DecryptTimingLogger timingLogger) {
        this.configId = configId;
        this.userTable = userTable;
        this.mapTable = mapTable;
        this.repo = repo;
        this.cipher = cipher;
        this.hashService = hashService;
        this.passwordService = passwordService;
        this.timingLogger = timingLogger;
    }

    @Override
    public LoginResult login(String email, String password) {
        String hash = hashService.hash(email);

        // 1) user 테이블에서 해시로 인증 대상 조회 (H1/H5가 측정하는 인덱스 조회 경로)
        return repo.findMapShapeUserByHash(userTable, hash)
                .map(user -> authenticateAndDecrypt(user, password, hash))
                .orElseGet(LoginResult::notFound);
    }

    private LoginResult authenticateAndDecrypt(MapShapeUserRow user, String password, String hash) {
        if (!passwordService.matches(password, user.passwordHash())) {
            return LoginResult.wrongPassword(0);
        }
        // 2) map 테이블에서 같은 해시로 암호문을 찾아 복호화 (순차 실행 — 계획서 2장 정정:
        //    user 조회와 map 조회는 동시에 일어나지 않으므로 O(log n + log m)이지 O(log n * log m)이 아니다)
        MapRow mapRow = repo.findMapRowByHash(mapTable, hash)
                .orElseThrow(() -> new IllegalStateException(
                        "정합성 오류: user에는 있지만 map에는 없는 해시 — " + userTable + "/" + mapTable));

        long start = System.nanoTime();
        String decrypted = cipher.decrypt(mapRow.emailAes256());
        long decryptNanos = System.nanoTime() - start;
        timingLogger.record(configId, "login", decryptNanos);

        return LoginResult.ok(user.id(), decrypted, decryptNanos);
    }

    @Override
    public long signup(String email, String rawPassword) {
        String hash = hashService.hash(email);
        if (repo.existsMapShapeUserByHash(userTable, hash)) {
            throw new DuplicateEmailException(email);
        }
        String passwordHash = passwordService.hash(rawPassword);
        String emailAes256 = cipher.encrypt(email);

        // user·map insert를 하나의 트랜잭션으로 묶는다 (계획서 7장) — 경계는 repo(Spring 빈)에 있다.
        return repo.insertMapShapeRowPair(userTable, mapTable, hash, passwordHash, emailAes256);
    }

    @Override
    public void withdraw(String email, String confirmPassword) {
        String hash = hashService.hash(email);
        MapShapeUserRow user = repo.findMapShapeUserByHash(userTable, hash)
                .orElseThrow(() -> new UserNotFoundException(email));
        if (!passwordService.matches(confirmPassword, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        // user·map 동시 삭제 — 고아 행이 남지 않게 같은 트랜잭션으로 묶는다 (계획서 11장 "삭제 정합성")
        repo.deleteMapShapeRowPair(userTable, mapTable, user.id(), hash);
    }
}
