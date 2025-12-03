package io.kneo.broadcaster.service.util;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record ValidationResult(boolean valid, String errorMessage, Map<String, List<String>> fieldErrors) {

    public static ValidationResult success() {
        return new ValidationResult(true, null, Collections.emptyMap());
    }

    public static ValidationResult failure(String errorMessage) {
        return new ValidationResult(false, errorMessage, Collections.emptyMap());
    }

    public static ValidationResult failure(String errorMessage, Map<String, List<String>> fieldErrors) {
        return new ValidationResult(false, errorMessage, fieldErrors == null ? Collections.emptyMap() : fieldErrors);
    }
}
