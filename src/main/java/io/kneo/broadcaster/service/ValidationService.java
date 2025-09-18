package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.SubmissionDTO;
import io.kneo.broadcaster.service.external.MailService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ValidationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationService.class);

    private final Validator validator;
    private final MailService mailService;

    @Inject
    public ValidationService(Validator validator, MailService mailService) {
        this.validator = validator;
        this.mailService = mailService;
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
            return ValidationResult.failure(errorMessage);
        }

        if (violations.isEmpty()) {
            return ValidationResult.success();
        }

        String errorMessage = violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));

        LOGGER.warn("Validation failed for SoundFragmentDTO: {}", errorMessage);
        return ValidationResult.failure(errorMessage);
    }

    public Uni<ValidationResult> validateSubmissionDTO(SubmissionDTO dto) {
        Set<ConstraintViolation<SubmissionDTO>> violations = validator.validate(dto);

        if (!violations.isEmpty()) {
            String errorMessage = violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .collect(Collectors.joining(", "));

            LOGGER.warn("Validation failed for SubmissionDTO: {}", errorMessage);
            return Uni.createFrom().item(ValidationResult.failure(errorMessage));
        }

        if (dto.getEmail() != null && !dto.getEmail().isEmpty()) {
            return mailService.verifyCode(dto.getEmail(), dto.getConfirmationCode())
                    .map(result -> {
                        if (result == null) {
                            mailService.removeCode(dto.getEmail());
                            return ValidationResult.success();
                        } else {
                            LOGGER.warn("Email verification failed for {}: {}", dto.getEmail(), result);
                            return ValidationResult.failure(result);
                        }
                    });
        }

        return Uni.createFrom().item(ValidationResult.success());
    }

}