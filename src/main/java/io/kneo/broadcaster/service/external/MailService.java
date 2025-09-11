package io.kneo.broadcaster.service.external;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@ApplicationScoped
public class MailService {

    @Inject
    SesClient sesClient;

    @ConfigProperty(name = "mail.from-email")
    String fromEmail;

    public void sendConfirmationCode(String email, String code) {
        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .source(fromEmail)
                    .destination(Destination.builder().toAddresses(email).build())
                    .message(Message.builder()
                            .subject(Content.builder().data("Email Confirmation").build())
                            .body(Body.builder()
                                    .text(Content.builder()
                                            .data("Your confirmation code is: " + code)
                                            .build())
                                    .build())
                            .build())
                    .build();

            SendEmailResponse response = sesClient.sendEmail(request);
            System.out.println("Email sent with ID: " + response.messageId());
        } catch (SesException e) {
            throw new RuntimeException("Failed to send confirmation email: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    public Uni<Void> sendConfirmationCodeAsync(String email, String code) {
        return Uni.createFrom().item(() -> {
            sendConfirmationCode(email, code);
            return (Void) null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    public void sendHtmlConfirmationCode(String email, String code) {
        try {
            String htmlBody = """
                <html>
                <body>
                    <h2>Email Confirmation</h2>
                    <p>Your confirmation code is: <strong>%s</strong></p>
                    <p>This code will expire in 15 minutes.</p>
                </body>
                </html>
                """.formatted(code);

            SendEmailRequest request = SendEmailRequest.builder()
                    .source(fromEmail)
                    .destination(Destination.builder().toAddresses(email).build())
                    .message(Message.builder()
                            .subject(Content.builder().data("Email Confirmation").build())
                            .body(Body.builder()
                                    .html(Content.builder().data(htmlBody).build())
                                    .text(Content.builder()
                                            .data("Your confirmation code is: " + code)
                                            .build())
                                    .build())
                            .build())
                    .build();

            SendEmailResponse response = sesClient.sendEmail(request);
            System.out.println("HTML email sent with ID: " + response.messageId());
        } catch (SesException e) {
            throw new RuntimeException("Failed to send HTML confirmation email: " + e.awsErrorDetails().errorMessage(), e);
        }
    }
}