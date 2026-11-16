package com.doh.ahbench.bench;

public final class BenchExceptions {

    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String email) {
            super("이미 가입된 이메일입니다: " + email);
        }
    }

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(String email) {
            super("존재하지 않는 이메일입니다: " + email);
        }
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("비밀번호가 일치하지 않습니다.");
        }
    }

    private BenchExceptions() {
    }
}
