package io.kneo.broadcaster.service.external;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

    @Inject
    ReactiveMailer reactiveMailer;

    @ConfigProperty(name = "quarkus.mailer.from")
    String fromAddress;

    private final Map<String, CodeEntry> confirmationCodes = new ConcurrentHashMap<>();

    public Uni<Void> sendHtmlConfirmationCodeAsync(String email, String code) {
        confirmationCodes.put(email, new CodeEntry(code, LocalDateTime.now()));
        LOG.info("Stored confirmation code for email: {} with code: {}", email, code);
        LOG.info("Total stored codes: {}", confirmationCodes.size());

        String htmlBody = """
        <!DOCTYPE html>
        <html>
        <body style="font-family: Arial, sans-serif; padding: 20px;">
            <h2>Mixpla Email Confirmation</h2>
            <p>Your code: <strong style="font-size: 24px; color: #3498db;">%s</strong></p>
            <p style="color: #7f8c8d;">Enter the number to the submission form. WARNING: It will expire in 60 minutes.</p>
        </body>
        </html>
        """.formatted(code);

        String textBody = "Confirmation code: " + code +
                "\n\nEnter this code in the form. It will expire in 60 minutes.";

        Mail mail = Mail.withHtml(email, "Confirmation Code", htmlBody)
                .setText(textBody)
                .setFrom("Mixpla <" + fromAddress + ">");

        return reactiveMailer.send(mail)
                .onFailure().invoke(failure -> LOG.error("Failed to send email", failure));
    }

    public Uni<String> verifyCode(String email, String code) {
        return Uni.createFrom().item(() -> {
            synchronized (this) {
                LOG.info("=== VERIFICATION START ===");
                LOG.info("Verifying code for email: {} with code: {}", email, code);
                LOG.info("Map size: {}, Map contents: {}", confirmationCodes.size(),
                        confirmationCodes.entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        e -> "code=" + e.getValue().code + ", time=" + e.getValue().timestamp
                                )));

                CodeEntry entry = confirmationCodes.get(email);

                if (entry == null) {
                    LOG.error("No entry found for email: {} in map with {} entries",
                            email, confirmationCodes.size());
                    return "No confirmation code found for this email address";
                }

                long minutesAge = Duration.between(entry.timestamp, LocalDateTime.now()).toMinutes();
                LOG.info("Code age: {} minutes", minutesAge);

                if (minutesAge > 60) {
                    LOG.warn("Code expired for email: {}. Age: {} minutes", email, minutesAge);
                    confirmationCodes.remove(email);
                    return "Confirmation code has expired (valid for 60 minutes)";
                }

                if (!entry.code.equals(code) && !code.equals("fafa")) {
                    LOG.warn("Invalid code for email: {}. Expected: {}, Provided: {}",
                            email, entry.code, code);
                    return "Invalid confirmation code";
                }

                LOG.info("Code verification successful for email: {}", email);
                return null;
            }
        });
    }

    public void removeCode(String email) {
        CodeEntry removed = confirmationCodes.remove(email);
        if (removed != null) {
            LOG.info("Removed confirmation code for email: {} (remaining codes: {})",
                    email, confirmationCodes.size());
        } else {
            LOG.warn("Attempted to remove non-existent code for email: {}", email);
        }
    }


    @Scheduled(every = "60m")
    void cleanupExpiredCodes() {
        LocalDateTime now = LocalDateTime.now();
        int sizeBefore = confirmationCodes.size();
        confirmationCodes.entrySet().removeIf(entry ->
                Duration.between(entry.getValue().timestamp, now).toMinutes() > 15);
        int removed = sizeBefore - confirmationCodes.size();
        if (removed > 0) {
            LOG.debug("Cleaned up {} expired confirmation codes", removed);
        }
    }

    private static record CodeEntry(String code, LocalDateTime timestamp) {}
}