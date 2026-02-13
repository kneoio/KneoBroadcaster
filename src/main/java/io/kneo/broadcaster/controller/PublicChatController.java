package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.ChatMessageDTO;
import io.kneo.broadcaster.service.chat.PublicChatService;
import io.kneo.broadcaster.service.chat.PublicChatSessionManager;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.UUID.randomUUID;

@ApplicationScoped
public class PublicChatController extends AbstractSecuredController<Object, Object> {
    private static final Logger LOG = LoggerFactory.getLogger(PublicChatController.class);
    private final PublicChatService publicChatService;
    private final Map<String, ServerWebSocket> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userStationRegistrations = new ConcurrentHashMap<>();

    public PublicChatController() {
        super(null);
        this.publicChatService = null;
    }

    @Inject
    public PublicChatController(UserService userService, PublicChatService publicChatService) {
        super(userService);
        this.publicChatService = publicChatService;
    }

    public void setupRoutes(Router router) {
        String path = "/api/chat";
        router.route(path + "*").handler(BodyHandler.create());
        router.route(path + "*").handler(this::addHeaders);
        router.post(path + "/send-code/:email").handler(this::sendCode);
        router.post(path + "/verify-code").handler(this::verifyCode);
        router.post(path + "/validate-token").handler(this::validateToken);  //when a user enter to chat it cheks
        router.post(path + "/register-listener").handler(this::registerListener);  //join chat button
        router.post(path + "/refresh-token").handler(this::refreshToken);
        
        router.route("/api/ws/public-chat").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                String token = rc.request().getParam("token");
                LOG.info("WebSocket connection attempt with token: {}", token);
                
                authenticateUserFromToken(token)
                        .subscribe().with(
                                user -> {
                                    LOG.info("User authenticated: {}", user.getUserName());
                                    rc.request().toWebSocket().onSuccess(ws -> handlePublicChatWebSocket(ws, user))
                                            .onFailure(err -> {
                                                LOG.error("WebSocket connection failed", err);
                                                rc.fail(500, err);
                                            });
                                },
                                err -> {
                                    LOG.warn("Authentication failed for token: {}", token);
                                    rc.response().setStatusCode(401).end("Invalid or expired token");
                                }
                        );
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });
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

            assert publicChatService != null;
            publicChatService.sendCode(email)
                    .subscribe().with(
                            v -> rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject()
                                            .put("success", true)
                                            .put("message", "Code sent to " + email)
                                            .encode()),
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

    private void verifyCode(RoutingContext rc) {
        try {
            JsonObject body = rc.body().asJsonObject();
            String email = body.getString("email");
            String code = body.getString("code");

            if (email == null || email.isBlank() || code == null || code.isBlank()) {
                rc.response()
                        .setStatusCode(400)
                        .end(new JsonObject().put("error", "Email and code are required").encode());
                return;
            }

            assert publicChatService != null;
            PublicChatSessionManager.VerificationResult result = publicChatService.verifyCode(code, email);

            if (result.success()) {
                rc.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                                .put("success", true)
                                .put("sessionToken", result.sessionToken())
                                .put("message", result.message())
                                .encode());
            } else {
                rc.response()
                        .setStatusCode(400)
                        .end(new JsonObject()
                                .put("success", false)
                                .put("error", result.message())
                                .encode());
            }

        } catch (Exception e) {
            rc.fail(400, e);
        }
    }

    private void validateToken(RoutingContext rc) {
        try {
            JsonObject body = rc.body().asJsonObject();
            String token = body.getString("token");

            if (token == null || token.isBlank()) {
                rc.response()
                        .setStatusCode(400)
                        .end(new JsonObject().put("error", "Token is required").encode());
                return;
            }

            assert publicChatService != null;
            authenticateUserFromToken(token)
                    .subscribe().with(
                            user -> {
                                boolean isRegistered = user.getId() != io.kneo.core.model.user.AnonymousUser.ID;
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(new JsonObject()
                                                .put("success", true)
                                                .put("valid", true)
                                                .put("registered", isRegistered)
                                                .put("userId", user.getId())
                                                .put("username", user.getUserName())
                                                .encode());
                            },
                            throwable -> {
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(new JsonObject()
                                                .put("success", true)
                                                .put("valid", false)
                                                .put("registered", false)
                                                .encode());
                            }
                    );

        } catch (Exception e) {
            LOG.error("Error in validateToken", e);
            rc.fail(400, e);
        }
    }

    private void registerListener(RoutingContext rc) {
        try {
            JsonObject body = rc.body().asJsonObject();
            String sessionToken = body.getString("sessionToken");
            String stationSlug = body.getString("stationSlug");
            String nickname = body.getString("nickname");

            if (sessionToken == null || sessionToken.isBlank()) {
                rc.response()
                        .setStatusCode(401)
                        .end(new JsonObject().put("error", "Session token is required").encode());
                return;
            }

            if (stationSlug == null || stationSlug.isBlank()) {
                rc.response()
                        .setStatusCode(400)
                        .end(new JsonObject().put("error", "Station slug is required").encode());
                return;
            }

            assert publicChatService != null;
            publicChatService.registerListener(sessionToken, stationSlug, nickname)
                    .subscribe().with(
                            result -> rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject()
                                            .put("success", true)
                                            .put("userId", result.userId())
                                            .put("userToken", result.userToken())
                                            .put("message", "Listener token generated successfully")
                                            .encode()),
                            throwable -> {
                                LOG.error("Failed to register listener", throwable);
                                rc.response()
                                        .setStatusCode(500)
                                        .end(new JsonObject()
                                                .put("error", "Failed to register listener: " + throwable.getMessage())
                                                .encode());
                            }
                    );

        } catch (Exception e) {
            LOG.error("Error in registerListener", e);
            rc.fail(400, e);
        }
    }

    private void refreshToken(RoutingContext rc) {
        try {
            JsonObject body = rc.body().asJsonObject();
            String oldToken = body.getString("userToken");

            if (oldToken == null || oldToken.isBlank()) {
                rc.response()
                        .setStatusCode(400)
                        .end(new JsonObject().put("error", "User token is required").encode());
                return;
            }

            assert publicChatService != null;
            publicChatService.refreshToken(oldToken)
                    .subscribe().with(
                            newToken -> rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject()
                                            .put("success", true)
                                            .put("userToken", newToken)
                                            .put("message", "Token refreshed successfully")
                                            .encode()),
                            throwable -> {
                                if (throwable instanceof IllegalArgumentException) {
                                    rc.response()
                                            .setStatusCode(401)
                                            .end(new JsonObject().put("error", throwable.getMessage()).encode());
                                } else {
                                    LOG.error("Error in refreshToken", throwable);
                                    rc.response()
                                            .setStatusCode(500)
                                            .end(new JsonObject().put("error", "Failed to refresh token").encode());
                                }
                            }
                    );

        } catch (Exception e) {
            LOG.error("Error in refreshToken", e);
            rc.fail(400, e);
        }
    }

    private Uni<IUser> authenticateUserFromToken(String token) {
        assert publicChatService != null;
        return publicChatService.authenticateUserFromToken(token);
    }

    private void handlePublicChatWebSocket(ServerWebSocket webSocket, IUser user) {
        webSocket.accept();
        
        String connectionId = randomUUID().toString();
        activeConnections.put(connectionId, webSocket);
        LOG.info("Public chat WebSocket connected: {} for user: {}", connectionId, user.getUserName());

        webSocket.textMessageHandler(message -> {
            try {
                JsonObject msgJson = new JsonObject(message);
                String action = msgJson.getString("action");
                String brandSlug = msgJson.getString("brandSlug");
                
                switch (action) {
                    case "sendMessage":
                        handleUserMessage(webSocket, msgJson, connectionId, brandSlug, user);
                        break;
                    case "getHistory":
                        handleGetHistory(webSocket, msgJson, user);
                        break;
                    default:
                        sendError(webSocket, "Unknown action: " + action);
                }
            } catch (Exception e) {
                LOG.error("Error processing message", e);
                sendError(webSocket, "Invalid message format: " + e.getMessage());
            }
        });

        webSocket.closeHandler(v -> {
            activeConnections.remove(connectionId);
            userStationRegistrations.remove(connectionId);
            LOG.info("Public chat WebSocket closed: {}", connectionId);
        });

        webSocket.exceptionHandler(err -> {
            LOG.error("WebSocket error for {}", connectionId, err);
            activeConnections.remove(connectionId);
            userStationRegistrations.remove(connectionId);
        });
    }

    private void handleUserMessage(ServerWebSocket webSocket, JsonObject msgJson, String connectionId, 
                                  String brandSlug, IUser user) {
        String username = msgJson.getString("username", user.getUserName());
        String content = msgJson.getString("content");

        if (content == null || content.trim().isEmpty()) {
            sendError(webSocket, "Message content cannot be empty");
            return;
        }

        assert publicChatService != null;
        
        Set<String> registeredStations = userStationRegistrations.computeIfAbsent(connectionId, k -> ConcurrentHashMap.newKeySet());
        
        Uni<Void> ensureRegistration;
        if (!registeredStations.contains(brandSlug)) {
            ensureRegistration = publicChatService.ensureUserIsListenerOfStation(user.getId(), brandSlug)
                    .invoke(() -> registeredStations.add(brandSlug));
        } else {
            ensureRegistration = Uni.createFrom().voidItem();
        }
        
        ensureRegistration
                .chain(() -> publicChatService.processUserMessage(username, content, connectionId, brandSlug, user))
                .subscribe().with(
                        response -> {
                            webSocket.writeTextMessage(response);
                            sendBotResponse(webSocket, content, connectionId, brandSlug, user);
                        },
                        err -> {
                            LOG.error("Error processing user message", err);
                            sendError(webSocket, err);
                        }
                );
    }

    private void sendBotResponse(ServerWebSocket webSocket, String userMessage, String connectionId, 
                                String brandSlug, IUser user) {
        assert publicChatService != null;
        publicChatService.generateBotResponse(
                userMessage,
                webSocket::writeTextMessage,
                webSocket::writeTextMessage,
                connectionId,
                brandSlug,
                user
        ).subscribe().with(
                v -> {},
                e -> {
                    LOG.error("Bot response error", e);
                    sendError(webSocket, "Bot response failed: " + e.getMessage());
                }
        );
    }

    private void handleGetHistory(ServerWebSocket webSocket, JsonObject msgJson, IUser user) {
        String brandSlug = msgJson.getString("brandSlug");
        Integer limit = msgJson.getInteger("limit", 50);

        assert publicChatService != null;
        publicChatService.getChatHistory(brandSlug, limit, user)
                .subscribe().with(
                        webSocket::writeTextMessage,
                        err -> {
                            LOG.error("Error getting chat history", err);
                            sendError(webSocket, err);
                        }
                );
    }

    private void sendError(ServerWebSocket webSocket, Throwable err) {
        sendError(webSocket, err.getMessage());
    }

    private void sendError(ServerWebSocket webSocket, String message) {
        webSocket.writeTextMessage(ChatMessageDTO.error(message, "system", "system").build().toJson());
    }
}