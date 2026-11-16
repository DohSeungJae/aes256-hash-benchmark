package com.doh.ahbench.bench;

import com.doh.ahbench.bench.BenchExceptions.DuplicateEmailException;
import com.doh.ahbench.bench.BenchExceptions.InvalidCredentialsException;
import com.doh.ahbench.bench.BenchExceptions.UserNotFoundException;
import com.doh.ahbench.common.ConfigId;
import com.doh.ahbench.crypto.Aes256CipherService;
import com.doh.ahbench.crypto.HashService;
import com.doh.ahbench.repo.BenchRowRepository;
import com.doh.ahbench.repo.Rows.InlineUserRow;
import com.doh.ahbench.security.PasswordService;

/**
 * 구성 E/F (+ E_HMAC/F_HMAC): H6 — map 테이블 없이 user 테이블 한 곳에
 * email_hash + email_aes256을 같이 둔다. 쿼리 한 번으로 인증·복호화에 필요한 값을
 * 모두 가져온다 (MAP_TABLE 모양처럼 두 테이블을 순차 조회하지 않음).
 *
 * E/F의 차이는 Flyway DDL의 인덱스 종류(B-tree/hash)에만 있다.
 */
public class InlineConfigHandler implements BenchmarkOperations {

    private final ConfigId configId;
    private final String userTable;
    private final BenchRowRepository repo;
    private final Aes256CipherService cipher;
    private final HashService hashService;
    private final PasswordService passwordService;
    private final DecryptTimingLogger timingLogger;

    public InlineConfigHandler(
            ConfigId configId,
            String userTable,
            BenchRowRepository repo,
            Aes256CipherService cipher,
            HashService hashService,
            PasswordService passwordService,
            DecryptTimingLogger timingLogger) {
        this.configId = configId;
        this.userTable = userTable;
        this.repo = repo;
        this.cipher = cipher;
        this.hashService = hashService;
        this.passwordService = passwordService;
        this.timingLogger = timingLogger;
    }

    @Override
    public LoginResult login(String email, String password) {
        String hash = hashService.hash(email);
        return repo.findInlineUserByHash(userTable, hash)
                .map(user -> authenticateAndDecrypt(user, password))
                .orElseGet(LoginResult::notFound);
    }

    private LoginResult authenticateAndDecrypt(InlineUserRow user, String password) {
        if (!passwordService.matches(password, user.passwordHash())) {
            return LoginResult.wrongPassword(0);
        }
        long start = System.nanoTime();
        String decrypted = cipher.decrypt(user.emailAes256());
        long decryptNanos = System.nanoTime() - start;
        timingLogger.record(configId, "login", decryptNanos);
        return LoginResult.ok(user.id(), decrypted, decryptNanos);
    }

    @Override
    public long signup(String email, String rawPassword) {
        String hash = hashService.hash(email);
        if (repo.existsInlineUserByHash(userTable, hash)) {
            throw new DuplicateEmailException(email);
        }
        String passwordHash = passwordService.hash(rawPassword);
        String emailAes256 = cipher.encrypt(email);
        return repo.insertInlineUser(userTable, hash, emailAes256, passwordHash);
    }

    @Override
    public void withdraw(String email, String confirmPassword) {
        String hash = hashService.hash(email);
        InlineUserRow user = repo.findInlineUserByHash(userTable, hash)
                .orElseThrow(() -> new UserNotFoundException(email));
        if (!passwordService.matches(confirmPassword, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        repo.deleteInlineUser(userTable, user.id());
    }
}
