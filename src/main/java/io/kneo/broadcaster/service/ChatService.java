package io.kneo.broadcaster.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class ChatService {

    private final List<JsonObject> chatHistory = new ArrayList<>();
    private final AnthropicClient anthropicClient;

    @Inject
    public ChatService(BroadcasterConfig config) {
        this.anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
                .build();
    }

    public Uni<String> sendMessage(String username, String content, String connectionId) {
        return Uni.createFrom().item(() -> {
            JsonObject message = createMessage(
                    username,
                    content,
                    System.currentTimeMillis(),
                    connectionId
            );
            
            chatHistory.add(message);
            
            if (chatHistory.size() > 100) {
                chatHistory.remove(0);
            }
            
            JsonObject response = new JsonObject()
                    .put("type", "message")
                    .put("data", message);
            
            return response.encode();
        });
    }

    public Uni<String> getChatHistory(int limit) {
        return Uni.createFrom().item(() -> {
            int startIndex = Math.max(0, chatHistory.size() - limit);
            List<JsonObject> recentMessages = chatHistory.subList(startIndex, chatHistory.size());
            
            JsonArray messages = new JsonArray();
            recentMessages.forEach(messages::add);
            
            JsonObject response = new JsonObject()
                    .put("type", "history")
                    .put("messages", messages);
            
            return response.encode();
        });
    }

    public Uni<String> generateBotResponse(String userMessage) {
        return Uni.createFrom().item(() -> {
            MessageCreateParams params = MessageCreateParams.builder()
                    .maxTokens(1024L)
                    .addUserMessage(userMessage)
                    .model(Model.CLAUDE_3_5_SONNET_20241022)
                    .build();
            
            Message response = anthropicClient.messages().create(params);
            ContentBlock contentBlock = response.content().get(0);
            
            String botResponse = "";
            if (contentBlock.text().isPresent()) {
                TextBlock textBlock = contentBlock.text().get();
                botResponse = textBlock.text();
            }
            
            JsonObject message = createMessage(
                    "ChatBot",
                    botResponse,
                    System.currentTimeMillis(),
                    "bot-" + UUID.randomUUID().toString()
            );
            
            chatHistory.add(message);
            
            JsonObject jsonResponse = new JsonObject()
                    .put("type", "message")
                    .put("data", message);
            
            return jsonResponse.encode();
        }).runSubscriptionOn(getDefaultWorkerPool());
    }

    private JsonObject createMessage(String username, String content, long timestamp, String connectionId) {
        return new JsonObject()
                .put("id", UUID.randomUUID().toString())
                .put("username", username)
                .put("content", content)
                .put("timestamp", timestamp)
                .put("connectionId", connectionId)
                .put("isBot", username.equals("ChatBot"));
    }
}
