package io.kneo.broadcaster.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.Tool.InputSchema;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.core.localization.LanguageCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class ChatService {

    private final List<JsonObject> chatHistory = new ArrayList<>();
    private final List<MessageParam> conversationHistory = new ArrayList<>();
    private final AnthropicClient anthropicClient;
    private final AiHelperService aiHelperService;

    @Inject
    public ChatService(BroadcasterConfig config, AiHelperService aiHelperService) {
        this.anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
                .build();
        this.aiHelperService = aiHelperService;
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
        String systemPrompt = "You are a helpful radio station assistant. When users ask about available stations, use the get_stations tool. Be friendly, concise, and informative.";
        
        InputSchema schema = InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "country",
                        Map.of(
                                "type", "string",
                                "description", "Filter stations by country code (e.g., 'US', 'UK')"),
                        "query",
                        Map.of(
                                "type", "string",
                                "description", "Search query to filter stations by name"))))
                .build();
        
        // Add user message to history
        MessageParam userMsg = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofString(userMessage))
                .build();
        conversationHistory.add(userMsg);
        
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .maxTokens(1024L)
                .system(systemPrompt)
                .messages(conversationHistory)
                .model(Model.CLAUDE_3_OPUS_20240229)
                .addTool(Tool.builder()
                        .name("get_stations")
                        .description("Get a list of available radio stations")
                        .inputSchema(schema)
                        .build());
        
        // First call - check if Claude wants to use a tool
        return Uni.createFrom().completionStage(() ->
                anthropicClient.async().messages().create(paramsBuilder.build())
        ).flatMap(message -> {
            // Check for tool use
            System.out.println("=== Checking for tool use ===");
            System.out.println("Message content blocks: " + message.content().size());
            
            Optional<ToolUseBlock> toolUse = message.content().stream()
                    .flatMap(block -> block.toolUse().stream())
                    .findFirst();
            
            if (toolUse.isPresent()) {
                System.out.println("Tool use detected: " + toolUse.get().name());
                // Handle tool call
                return handleToolCall(toolUse.get(), paramsBuilder, chunkHandler, completionHandler, connectionId);
            } else {
                System.out.println("No tool use detected, streaming response");
                // No tool use, stream the response
                return streamResponse(paramsBuilder.build(), chunkHandler, completionHandler, connectionId);
            }
        }).runSubscriptionOn(getDefaultWorkerPool());
    }
    
    private Uni<Void> handleToolCall(ToolUseBlock toolUse, MessageCreateParams.Builder paramsBuilder,
                                      Consumer<String> chunkHandler, Consumer<String> completionHandler, String connectionId) {
        System.out.println("=== handleToolCall called ===");
        System.out.println("Tool name: " + toolUse.name());
        
        if (!"get_stations".equals(toolUse.name())) {
            return Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
        }
        
        // Extract parameters
        Map<String, JsonValue> toolParams = (Map<String, JsonValue>) toolUse._input().asObject().get();
        String country = toolParams.containsKey("country") && toolParams.get("country").asString().isPresent() 
                ? toolParams.get("country").asString().get().toString() : null;
        String query = toolParams.containsKey("query") && toolParams.get("query").asString().isPresent() 
                ? toolParams.get("query").asString().get().toString() : null;
        
        System.out.println("Tool parameters - country: " + country + ", query: " + query);
        System.out.println("Calling aiHelperService.getAllStations...");
        
        // Call getAllStations
        return aiHelperService.getAllStations(null, country, null, query)
                .onItem().invoke(stationsData -> {
                    System.out.println("getAllStations returned: " + stationsData.getRadioStations().size() + " stations");
                })
                .onFailure().invoke(err -> {
                    System.err.println("getAllStations failed: " + err.getMessage());
                    err.printStackTrace();
                })
                .flatMap(stationsData -> {
                    System.out.println("Formatting station data...");
                    // Format station data as JSON
                    JsonArray stationsJson = new JsonArray();
                    stationsData.getRadioStations().forEach(station -> {
                        JsonObject stationObj = new JsonObject()
                                .put("name", station.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                                .put("slugName", station.getSlugName())
                                .put("country", station.getCountry())
                                .put("status", station.getRadioStationStatus().toString());
                        stationsJson.add(stationObj);
                    });
                    
                    System.out.println("Adding tool use to history...");
                    // Add assistant tool use to history
                    MessageParam assistantMsg = MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .content(MessageParam.Content.ofBlockParams(
                                    List.of(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                                            .name(toolUse.name())
                                            .id(toolUse.id())
                                            .input(toolUse._input())
                                            .build()))))
                            .build();
                    conversationHistory.add(assistantMsg);
                    
                    System.out.println("Adding tool result to history...");
                    // Add tool result to history
                    MessageParam toolResultMsg = MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(MessageParam.Content.ofBlockParams(
                                    List.of(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                                            .toolUseId(toolUse.id())
                                            .content(stationsJson.encode())
                                            .build()))))
                            .build();
                    conversationHistory.add(toolResultMsg);
                    
                    System.out.println("Making second API call with tool results...");
                    // Second call with tool result - rebuild params with updated history
                    MessageCreateParams secondParams = MessageCreateParams.builder()
                            .maxTokens(1024L)
                            .system("You are a helpful radio station assistant. When users ask about available stations, use the get_stations tool. Be friendly, concise, and informative.")
                            .messages(conversationHistory)
                            .model(Model.CLAUDE_3_OPUS_20240229)
                            .addTool(Tool.builder()
                                    .name("get_stations")
                                    .description("Get a list of available radio stations")
                                    .inputSchema(InputSchema.builder()
                                            .properties(JsonValue.from(Map.of(
                                                    "country",
                                                    Map.of(
                                                            "type", "string",
                                                            "description", "Filter stations by country code (e.g., 'US', 'UK')"),
                                                    "query",
                                                    Map.of(
                                                            "type", "string",
                                                            "description", "Search query to filter stations by name"))))
                                            .build())
                                    .build())
                            .build();
                    return streamResponse(secondParams, chunkHandler, completionHandler, connectionId);
                });
    }
    
    private Uni<Void> streamResponse(MessageCreateParams params, Consumer<String> chunkHandler, Consumer<String> completionHandler, String connectionId) {
        return Uni.createFrom().completionStage(() -> {
            StringBuilder fullResponse = new StringBuilder();
            boolean[] inThinking = {false};
            
            return anthropicClient.async().messages().createStreaming(params)
                    .subscribe(new AsyncStreamResponse.Handler<>() {
                        @Override
                        public void onNext(RawMessageStreamEvent chunk) {
                            try {
                                if (chunk.contentBlockDelta().isPresent()) {
                                    RawContentBlockDelta delta = chunk.contentBlockDelta().get().delta();
                                    if (delta.text().isPresent()) {
                                        String chunkText = delta.text().get().text();
                                        fullResponse.append(chunkText);
                                        
                                        // Track thinking tags and filter them out
                                        if (chunkText.contains("<thinking>")) {
                                            inThinking[0] = true;
                                        }
                                        if (chunkText.contains("</thinking>")) {
                                            inThinking[0] = false;
                                        }
                                        
                                        // Only send chunks outside thinking blocks
                                        if (!inThinking[0] && !chunkText.contains("<thinking>") && !chunkText.contains("</thinking>")) {
                                            JsonObject chunkMessage = new JsonObject()
                                                    .put("type", "chunk")
                                                    .put("content", chunkText)
                                                    .put("connectionId", connectionId);
                                            chunkHandler.accept(chunkMessage.encode());
                                        }
                                    }
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
                                String responseText = fullResponse.toString().replaceAll("(?s)<thinking>.*?</thinking>", "").trim();
                                
                                // Only add to history if response is not empty
                                if (!responseText.isEmpty()) {
                                    MessageParam assistantResponse = MessageParam.builder()
                                            .role(MessageParam.Role.ASSISTANT)
                                            .content(MessageParam.Content.ofString(responseText))
                                            .build();
                                    conversationHistory.add(assistantResponse);
                                    
                                    JsonObject botMessage = createMessage("ChatBot", responseText, System.currentTimeMillis(), connectionId);
                                    chatHistory.add(botMessage);
                                    
                                    JsonObject completeMessage = new JsonObject()
                                            .put("type", "message")
                                            .put("data", botMessage);
                                    completionHandler.accept(completeMessage.encode());
                                }
                            }
                        }
                    })
                    .onCompleteFuture();
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
