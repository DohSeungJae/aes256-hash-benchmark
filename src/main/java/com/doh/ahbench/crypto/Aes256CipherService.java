package com.doh.ahbench.crypto;

import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.jasypt.salt.RandomSaltGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 블로그의 PIEncryptor와 같은 역할(AES256 양방향 암호화)을 하지만, 내부 구현은
 * {@code AES256TextEncryptor}가 아니라 {@code PooledPBEStringEncryptor}를 직접 쓴다.
 *
 * 이유: 계획서 6장 "생성기" 절이 명시하듯 {@code AES256TextEncryptor}는 스레드 안전성이
 * 보장되지 않는다. 데이터 생성기만이 아니라 이 서버도 k6 VU 여러 개가 동시에 같은
 * 빈(singleton)을 호출하므로 똑같이 문제가 된다. {@code PooledPBEStringEncryptor}에
 * 풀 크기를 지정해 동시성 문제 없이 재사용한다.
 *
 * 키 유도 알고리즘은 bench.encryption.algorithm으로 고른다 (sha256 | sha512).
 * 둘은 서로의 암호문을 복호화하지 못한다.
 * - sha256: PBEWithHMACSHA256AndAES_256 (기본값). 내장 generate 프로필과 generator/ 독립 생성기도 이 방식.
 * - sha512: PBEWithHMACSHA512AndAES_256 — jasypt 1.9.3 AES256TextEncryptor와 동일.
 *           AES256TextEncryptor로 만든 외부 데이터를 읽어야 할 때만 쓴다.
 *
 * 모든 구성(A, B~F, HMAC 변형)이 같은 키·같은 구현을 공유한다 (계획서 9장, 공정성 조건).
 */
@Component
public class Aes256CipherService {

    private final PooledPBEStringEncryptor encryptor;

    public Aes256CipherService(
            @Value("${bench.encryption.key}") String encryptionKey,
            @Value("${bench.encryption.pool-size:20}") int poolSize,
            @Value("${bench.encryption.algorithm:sha256}") String algorithm) {
        this.encryptor = new PooledPBEStringEncryptor();
        encryptor.setPoolSize(poolSize);
        encryptor.setAlgorithm(pbeAlgorithm(algorithm));
        encryptor.setPassword(encryptionKey);
        encryptor.setIvGenerator(new RandomIvGenerator());
        encryptor.setSaltGenerator(new RandomSaltGenerator());
        encryptor.setStringOutputType("base64");
    }

    private static String pbeAlgorithm(String algorithm) {
        return switch (algorithm.trim().toLowerCase()) {
            case "sha256" -> "PBEWITHHMACSHA256ANDAES_256";
            case "sha512" -> "PBEWITHHMACSHA512ANDAES_256";
            default -> throw new IllegalArgumentException(
                    "bench.encryption.algorithm은 sha256 또는 sha512여야 합니다: " + algorithm);
        };
    }

    public String encrypt(String plaintext) {
        return encryptor.encrypt(plaintext);
    }

    public String decrypt(String ciphertext) {
        return encryptor.decrypt(ciphertext);
    }
}
