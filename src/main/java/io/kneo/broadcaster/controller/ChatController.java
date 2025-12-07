package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.ChatMessageDTO;
import io.kneo.broadcaster.service.chat.OwnerChatService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.UndefinedUser;
import io.kneo.core.repository.exception.UserNotFoundException;
import io.kneo.core.service.UserService;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.UUID.randomUUID;

@ApplicationScoped
public class ChatController extends AbstractSecuredController<Object, Object> {

    private UserService userService;
    private OwnerChatService ownerChatService;
    private JWTParser jwtParser;
    private final Map<String, ServerWebSocket> activeConnections = new ConcurrentHashMap<>();

    public ChatController() {
        super(null);
    }

    @Inject
    public ChatController(UserService userService, OwnerChatService ownerChatService, JWTParser jwtParser) {
        super(userService);
        this.userService = userService;
        this.ownerChatService = ownerChatService;
        this.jwtParser = jwtParser;
    }

    public void setupRoutes(Router router) {
        router.route("/api/ws/chat").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                getContextUserFromWebSocket(rc)
                        .subscribe().with(
                                user -> rc.request().toWebSocket().onSuccess(ws -> handleChatWebSocket(ws, user))
                                        .onFailure(err -> {
                                            System.err.println("WebSocket connection failed: " + err.getMessage());
                                            rc.fail(500, err);
                                        }),
                                err -> {
                                    System.err.println("Authentication required for chat: " + err.getMessage());
                                    rc.fail(401, err);
                                }
                        );
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });
    }

    private void handleChatWebSocket(ServerWebSocket webSocket, IUser user) {
        webSocket.accept();
        
        String connectionId = randomUUID().toString();
        activeConnections.put(connectionId, webSocket);

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
                sendError(webSocket, "Invalid message format: " + e.getMessage());
            }
        });

        webSocket.closeHandler(v -> {
            activeConnections.remove(connectionId);
            System.out.println("WebSocket closed: " + connectionId);
        });

        webSocket.exceptionHandler(err -> {
            System.err.println("WebSocket error for " + connectionId + ": " + err.getMessage());
            activeConnections.remove(connectionId);
        });
    }

    private void handleUserMessage(ServerWebSocket webSocket, JsonObject msgJson, String connectionId, String brandSlug, IUser user) {
        String username = msgJson.getString("username", "Anonymous");
        String content = msgJson.getString("content");

        if (content == null || content.trim().isEmpty()) {
            sendError(webSocket, "Message content cannot be empty");
            return;
        }

        ownerChatService.processUserMessage(username, content, connectionId, brandSlug, user)
                .subscribe().with(
                        response -> {
                            webSocket.writeTextMessage(response);
                            sendBotResponse(webSocket, content, connectionId, brandSlug, user);
                        },
                        err -> sendError(webSocket, err)
                );
    }

    private void sendBotResponse(ServerWebSocket webSocket, String userMessage, String connectionId, String brandSlug, IUser user) {
        ownerChatService.generateBotResponse(
                userMessage, chunk -> webSocket.writeTextMessage(chunk), response -> webSocket.writeTextMessage(response), connectionId, brandSlug, user
        ).subscribe().with(
                v -> {},
                e -> {
                    System.err.println("Bot response error: " + e.getMessage());
                    sendError(webSocket, "Bot response failed: " + e.getMessage());
                }
        );
    }

    private void handleGetHistory(ServerWebSocket webSocket, JsonObject msgJson, io.kneo.core.model.user.IUser user) {
        if (user == null) {
            sendError(webSocket, "Authentication required to access chat history");
            return;
        }

        String brandSlug = msgJson.getString("brandSlug");
        Integer limit = msgJson.getInteger("limit", 50);

        ownerChatService.getChatHistory(brandSlug, limit, user)
                .subscribe().with(
                        webSocket::writeTextMessage,
                        err -> sendError(webSocket, err)
                );
    }

    private void sendError(ServerWebSocket webSocket, Throwable err) {
        sendError(webSocket, err.getMessage());
    }

    private void sendError(ServerWebSocket webSocket, String message) {
        webSocket.writeTextMessage(ChatMessageDTO.error(message, "system", "system").build().toJson());
    }

    protected Uni<IUser> getContextUserFromWebSocket(RoutingContext rc) {
        String token = rc.request().getParam("token");

        if (token == null || token.isEmpty()) {
            return Uni.createFrom().failure(new IllegalStateException("No token provided"));
        }

        // Validate the token and extract username
        // This depends on your JWT validation logic
        try {
            String username = validateTokenAndGetUsername(token);

            return userService.findByLogin(username)
                    .onItem().transformToUni(user -> {
                        if (user != null && !(user instanceof UndefinedUser)) {
                            return Uni.createFrom().item(user);
                        } else {
                            return Uni.createFrom().failure(new UserNotFoundException(username));
                        }
                    });
        } catch (Exception e) {
            return Uni.createFrom().failure(new IllegalStateException("Invalid token: " + e.getMessage()));
        }
    }

    private String validateTokenAndGetUsername(String token) throws ParseException {
        JsonWebToken jwt = jwtParser.parse(token);
        
        String username = jwt.getClaim("preferred_username");
        if (username == null || username.isEmpty()) {
            username = jwt.getName();
        }
        
        if (username == null || username.isEmpty()) {
            throw new IllegalStateException("No username found in JWT token");
        }
        
        return username;
    }
}
