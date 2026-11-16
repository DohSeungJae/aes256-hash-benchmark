package com.doh.ahbench.web;

import jakarta.validation.constraints.NotBlank;

public final class Dtos {

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record LoginResponse(boolean success, Long userId, String email, long decryptNanos) {
    }

    public record SignupRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record SignupResponse(long userId) {
    }

    public record WithdrawRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record ErrorResponse(String error, String message) {
    }

    private Dtos() {
    }
}
