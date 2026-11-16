package com.doh.ahbench.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 비밀번호는 복호화가 불가능한 단방향 알고리즘(bcrypt)으로 저장한다 (계획서 11장 "비밀번호는 별도").
 * 모든 구성(A~F, HMAC 변형)이 동일하게 이 서비스를 쓴다 — 비밀번호 처리 방식은
 * 이 프로젝트의 비교 대상(이메일 조회 구조)과 무관하므로 변수에서 제외한다.
 */
@Component
public class PasswordService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String rawPassword) { //이름 중복? 
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String hashedPassword) {
        return encoder.matches(rawPassword, hashedPassword);
    }
}
