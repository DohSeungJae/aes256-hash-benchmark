package com.doh.ahbench.crypto;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 블로그 원안과 동일한 방식: 솔트/키 없는 SHA-256을 Base64로 인코딩.
 * 블로그 「문제 해결 11」의 PIEncryptor.hashify()와 동일한 구현.
 *
 * 계획서 11장이 지적하듯 이메일처럼 입력 공간이 좁은 값에는 사전 대입 공격에
 * 취약하다. 구성 B/C/D에서 이 구현을 쓰고, HMAC 변형(C_HMAC 등)과 성능을 비교한다.
 */
@Component
public class Sha256HashService implements HashService {

    @Override
    public String hash(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encodedHash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM 표준 구현에 포함되므로 실질적으로 발생하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 찾을 수 없습니다.", e);
        }
    }

    @Override
    public HashAlgo algo() {
        return HashAlgo.SHA256;
    }
}
