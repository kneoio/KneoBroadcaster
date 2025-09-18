package io.kneo.broadcaster.service;

public record ValidationResult(boolean valid, String errorMessage) {

    public static ValidationResult success() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult failure(String errorMessage) {
        return new ValidationResult(false, errorMessage);
    }
}
