package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.SoundFragmentDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ValidationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationService.class);

    private final Validator validator;

    @Inject
    public ValidationService(Validator validator) {
        this.validator = validator;
    }

    public ValidationResult validateSoundFragmentDTO(SoundFragmentDTO dto) {
        Set<ConstraintViolation<SoundFragmentDTO>> violations = validator.validate(dto);

        if (dto.getId() == null && (dto.getNewlyUploaded() == null || dto.getNewlyUploaded().isEmpty())) {
            violations = new HashSet<>(violations);
            String errorMessage = violations.isEmpty() ?
                    "Music file is required - either provide an existing ID or upload new files" :
                    violations.stream()
                            .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                            .collect(Collectors.joining(", ")) +
                            ", Music file is required - either provide an existing ID or upload new files";

            LOGGER.warn("Validation failed for SoundFragmentDTO: {}", errorMessage);
            return ValidationResult.failure(errorMessage, violations);
        }

        if (violations.isEmpty()) {
            return ValidationResult.success();
        }

        String errorMessage = violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));

        LOGGER.warn("Validation failed for SoundFragmentDTO: {}", errorMessage);
        return ValidationResult.failure(errorMessage, violations);
    }

    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final Set<ConstraintViolation<SoundFragmentDTO>> violations;

        private ValidationResult(boolean valid, String errorMessage, Set<ConstraintViolation<SoundFragmentDTO>> violations) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.violations = violations;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult failure(String errorMessage, Set<ConstraintViolation<SoundFragmentDTO>> violations) {
            return new ValidationResult(false, errorMessage, violations);
        }
    }
}