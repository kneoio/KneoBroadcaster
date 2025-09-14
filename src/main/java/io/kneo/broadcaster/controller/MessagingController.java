package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.service.external.MailService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.service.UserService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

@ApplicationScoped
public class MessagingController extends AbstractSecuredController<Object, Object> {
    private static final Logger LOG = LoggerFactory.getLogger(MessagingController.class);
    private final MailService mailService;
    private final SecureRandom secureRandom = new SecureRandom();

    public MessagingController() {
        super(null);
        this.mailService = null;
    }

    @Inject
    public MessagingController(UserService userService, MailService mailService) {
        super(userService);
        this.mailService = mailService;
    }

    public void setupRoutes(Router router) {
        String path = "/api/messaging";
        router.route(path + "*").handler(BodyHandler.create());
        router.route(path + "*").handler(this::addHeaders);
        router.post(path + "/send-code/:email").handler(this::sendCode);
    }

    private void sendCode(RoutingContext rc) {
        try {
            String email = rc.pathParam("email");

            if (email == null || email.isBlank()) {
                rc.response()
                        .setStatusCode(400)
                        .end(new JsonObject().put("error", "Email is required").encode());
                return;
            }

            String code = String.valueOf(1000 + secureRandom.nextInt(9000));

            assert mailService != null;
            mailService.sendHtmlConfirmationCodeAsync(email, code)
                    .subscribe().with(
                            v -> {
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(new JsonObject()
                                                .put("success", true)
                                                .put("message", "Code sent to " + email)
                                                .encode());
                            },
                            throwable -> {
                                LOG.error("Failed to send code to {}", email, throwable);
                                rc.response()
                                        .setStatusCode(500)
                                        .end(new JsonObject().put("error", "Failed to send code").encode());
                            }
                    );

        } catch (Exception e) {
            rc.fail(400, e);
        }
    }
}