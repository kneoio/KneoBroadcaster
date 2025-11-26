package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.service.chat.ChatService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.UUID.randomUUID;

@ApplicationScoped
public class ChatController extends AbstractSecuredController<Object, Object> {

    private ChatService chatService;
    private final Map<String, ServerWebSocket> activeConnections = new ConcurrentHashMap<>();

    public ChatController() {
        super(null);
    }

    @Inject
    public ChatController(io.kneo.core.service.UserService userService, ChatService chatService) {
        super(userService);
        this.chatService = chatService;
    }

    public void setupRoutes(Router router) {
        router.route("/api/ws/chat").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                getContextUser(rc, false, false)
                        .subscribe().with(
                                user -> rc.request().toWebSocket().onSuccess(ws -> handleChatWebSocket(ws, user))
                                        .onFailure(err -> {
                                            System.err.println("WebSocket connection failed: " + err.getMessage());
                                            rc.fail(500, err);
                                        }),
                                err -> rc.request().toWebSocket().onSuccess(ws -> handleChatWebSocket(ws, null))
                                        .onFailure(upgErr -> {
                                            System.err.println("WebSocket upgrade failed (no user): " + upgErr.getMessage());
                                            rc.fail(500, upgErr);
                                        })
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
                String stationId = msgJson.getString("stationId");
                switch (action) {
                    case "sendMessage":
                        handleUserMessage(webSocket, msgJson, connectionId, stationId, user);
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

    private void handleUserMessage(ServerWebSocket webSocket, JsonObject msgJson, String connectionId, String stationId, IUser user) {
        String username = msgJson.getString("username", "Anonymous");
        String content = msgJson.getString("content");

        if (content == null || content.trim().isEmpty()) {
            sendError(webSocket, "Message content cannot be empty");
            return;
        }

        IUser effectiveUser = (user != null) ? user : SuperUser.build();
        chatService.processUserMessage(username, content, connectionId, effectiveUser)
                .subscribe().with(
                        response -> {
                            webSocket.writeTextMessage(response);
                            sendBotResponse(webSocket, content, connectionId, stationId, effectiveUser);
                        },
                        err -> sendError(webSocket, err)
                );
    }

    private void sendBotResponse(ServerWebSocket webSocket, String userMessage, String connectionId, String stationId, IUser user) {
        chatService.generateBotResponse(
                userMessage, chunk -> webSocket.writeTextMessage(chunk), response -> webSocket.writeTextMessage(response), connectionId, stationId, user
        ).subscribe().with(
                v -> {},
                e -> {
                    System.err.println("Bot response error: " + e.getMessage());
                    sendError(webSocket, "Bot response failed: " + e.getMessage());
                }
        );
    }

    private void handleGetHistory(ServerWebSocket webSocket, JsonObject msgJson, io.kneo.core.model.user.IUser user) {
        Integer limit = msgJson.getInteger("limit", 50);

        chatService.getChatHistory(limit, user)
                .subscribe().with(
                        webSocket::writeTextMessage,
                        err -> sendError(webSocket, err)
                );
    }

    private void sendError(ServerWebSocket webSocket, Throwable err) {
        sendError(webSocket, err.getMessage());
    }

    private void sendError(ServerWebSocket webSocket, String message) {
        JsonObject error = new JsonObject()
                .put("type", "error")
                .put("message", message)
                .put("timestamp", System.currentTimeMillis());
        webSocket.writeTextMessage(error.encode());
    }
}
