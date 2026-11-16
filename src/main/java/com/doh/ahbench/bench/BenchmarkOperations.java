package com.doh.ahbench.bench;

/**
 * 모든 구성(A~F, HMAC 변형)이 공통으로 구현하는 조회/가입/탈퇴 동작.
 * k6 시나리오 S1~S5(계획서 8장)가 호출하는 API의 서버 쪽 경계다.
 */
public interface BenchmarkOperations {

    /** S1 로그인, S5 존재하지 않는 이메일 조회. 이메일·비밀번호가 맞으면 복호화된 이메일을 반환. */
    LoginResult login(String email, String password);

    /** S2 회원가입. */
    long signup(String email, String rawPassword);

    /** S3 회원탈퇴. */
    void withdraw(String email, String confirmPassword);
}
