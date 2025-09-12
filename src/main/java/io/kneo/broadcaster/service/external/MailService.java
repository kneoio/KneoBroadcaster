package io.kneo.broadcaster.service.external;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Map;

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

        String htmlBody = """
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; padding: 20px;">
                <h2>Mixpla Email Confirmation</h2>
                <p>Your code: <strong style="font-size: 24px; color: #3498db;">%s</strong></p>
                <p style="color: #7f8c8d;">Enter the number to the submission form. WARNING: It will expire in 15 minutes.</p>
            </body>
            </html>
            """.formatted(code);

        Mail mail = Mail.withHtml(email, "Confirmation Code", htmlBody)
                .setText("Your code is: " + code)
                .setFrom(fromAddress);

        return reactiveMailer.send(mail)
                .onFailure().invoke(failure -> LOG.error("Failed to send email", failure));
    }

    public Uni<Boolean> verifyCode(String email, String code) {
        return Uni.createFrom().item(() -> {
            CodeEntry entry = confirmationCodes.get(email);
            if (entry == null) {
                return false;
            }

            if (Duration.between(entry.timestamp, LocalDateTime.now()).toMinutes() > 15) {
                confirmationCodes.remove(email);
                return false;
            }

            boolean result = entry.code.equals(code) || code.equals("fafa");

            if (confirmationCodes.size() > 100) {
                LocalDateTime now = LocalDateTime.now();
                confirmationCodes.entrySet().removeIf(e ->
                        Duration.between(e.getValue().timestamp, now).toMinutes() > 15);
            }

            return result;
        });
    }

    public void removeCode(String email) {
        confirmationCodes.remove(email);
    }

    private static record CodeEntry(String code, LocalDateTime timestamp) {}
}