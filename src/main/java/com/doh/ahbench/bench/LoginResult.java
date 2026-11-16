package com.doh.ahbench.bench;

/**
 * @param success        이메일 존재 + 비밀번호 일치 여부
 * @param userId         success일 때만 값이 있음
 * @param decryptedEmail success일 때만 값이 있음 — AES256 복호화 결과
 * @param decryptNanos   이번 요청에서 AES 복호화에 쓴 시간(나노초). 못 찾았으면 0.
 *                       계획서 9장 "복호화 1회 소요 시간" 측정용.
 */
public record LoginResult(boolean success, Long userId, String decryptedEmail, long decryptNanos) {

    public static LoginResult notFound() {
        return new LoginResult(false, null, null, 0L);
    }

    public static LoginResult wrongPassword(long decryptNanos) {
        return new LoginResult(false, null, null, decryptNanos);
    }

    public static LoginResult ok(long userId, String decryptedEmail, long decryptNanos) {
        return new LoginResult(true, userId, decryptedEmail, decryptNanos);
    }
}
