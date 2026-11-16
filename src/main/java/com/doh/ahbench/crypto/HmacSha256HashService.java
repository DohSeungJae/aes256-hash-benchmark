package com.doh.ahbench.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 계획서 11장이 권장하는 정정: 서버 비밀키를 쓰는 HMAC-SHA256.
 * 결정적이라 SHA-256과 동일하게 조회 키로 쓸 수 있고, 키가 없으면 사전 대입 공격이
 * 불가능해 솔트 없는 SHA-256보다 안전하다. AES 암호화 키와는 별도의 키를 쓴다
 * (계획서 11장 "키 관리": AES 키와 HMAC 키 분리).
 *
 * C_HMAC / D_HMAC / E_HMAC / F_HMAC 구성에서 사용하며, 같은 구조의 SHA-256 구성(C/D/E/F)과
 * 성능 차이가 "거의 없음"을 확인하는 것이 계획서의 목표 중 하나다.
 */
@Component
public class HmacSha256HashService implements HashService {

    private static final String ALGO = "HmacSHA256";

    private final SecretKeySpec key;

    public HmacSha256HashService(@Value("${bench.hmac.key}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO);
    }

    @Override
    public String hash(String plaintext) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(key);
            byte[] result = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(result);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 계산에 실패했습니다.", e);
        }
    }

    @Override
    public HashAlgo algo() {
        return HashAlgo.HMAC_SHA256;
    }
}
