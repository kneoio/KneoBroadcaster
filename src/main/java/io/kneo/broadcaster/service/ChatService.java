package io.kneo.broadcaster.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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

    public Uni<String> processUserMessage(String username, String content, String connectionId) {
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

    public Uni<Void> generateBotResponse(String userMessage, Consumer<String> chunkHandler, Consumer<String> completionHandler, String connectionId) {
        return Uni.createFrom().completionStage(() -> {
            MessageCreateParams params = MessageCreateParams.builder()
                    .maxTokens(1024L)
                    .addUserMessage(userMessage)
                    .model(Model.CLAUDE_3_OPUS_20240229)
                    .build();
            
            StringBuilder fullResponse = new StringBuilder();

            return anthropicClient.async().messages().createStreaming(params)
                    .subscribe(new AsyncStreamResponse.Handler<RawMessageStreamEvent>() {
                        @Override
                        public void onNext(RawMessageStreamEvent chunk) {
                            try {
                                String chunkText = extractTextFromChunk(chunk);
                                if (chunkText != null && !chunkText.isEmpty()) {
                                    fullResponse.append(chunkText);

                                    JsonObject chunkMessage = new JsonObject()
                                            .put("type", "chunk")
                                            .put("content", chunkText)
                                            .put("connectionId", connectionId);

                                    chunkHandler.accept(chunkMessage.encode());
                                }
                            } catch (Exception e) {
                                System.err.println("Error processing chunk: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onComplete(Optional<Throwable> error) {
                            if (error.isPresent()) {
                                System.err.println("Stream error: " + error.get().getMessage());
                                JsonObject errorMessage = new JsonObject()
                                        .put("type", "error")
                                        .put("message", "Bot response failed: " + error.get().getMessage());
                                completionHandler.accept(errorMessage.encode());
                            } else {
                                JsonObject message = createMessage(
                                        "ChatBot",
                                        fullResponse.toString(),
                                        System.currentTimeMillis(),
                                        connectionId
                                );

                                chatHistory.add(message);

                                JsonObject completeMessage = new JsonObject()
                                        .put("type", "message")
                                        .put("data", message);

                                completionHandler.accept(completeMessage.encode());
                            }
                        }
                    })
                    .onCompleteFuture();
        }).runSubscriptionOn(getDefaultWorkerPool());
    }
    
    private String extractTextFromChunk(RawMessageStreamEvent chunk) {
        try {
            if (chunk.contentBlockDelta().isPresent()) {
                var delta = chunk.contentBlockDelta().get().delta();
                if (delta.text().isPresent()) {
                    return delta.text().get().text();
                }
            }
        } catch (Exception e) {
            // Silently ignore parsing errors for non-text chunks
        }
        return null;
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
