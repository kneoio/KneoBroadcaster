package io.kneo.broadcaster.service.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.AddToQueueTool;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.GetStations;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.SearchBrandSoundFragments;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class ChatService {

    private final List<JsonObject> chatHistory = new ArrayList<>();
    private final List<MessageParam> conversationHistory = new ArrayList<>();
    private final AnthropicClient anthropicClient;
    private final AiHelperService aiHelperService;
    private final String mainPrompt;
    private final String systemPromptCall2;
    private final BroadcasterConfig config;
    private final ConcurrentHashMap<String, String> assistantNameByConnectionId = new ConcurrentHashMap<>();
    @Inject
    RadioStationService radioStationService;
    @Inject
    AiAgentService aiAgentService;
    @Inject
    QueueService queueService;

    @Inject
    public ChatService(BroadcasterConfig config, AiHelperService aiHelperService) {
        this.anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
                .build();
        this.aiHelperService = aiHelperService;
        this.config = config;
        this.mainPrompt = loadResourceAsString("/prompts/systemPrompt.hbs");
        this.systemPromptCall2 = loadResourceAsString("/prompts/systemPrompt2.hbs");
    }

    public Uni<String> processUserMessage(String username, String content, String connectionId, IUser user) {
        return Uni.createFrom().item(() -> {
            JsonObject message = createMessage(
                    username,
                    content,
                    System.currentTimeMillis(),
                    connectionId,
                    false
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

    public Uni<String> getChatHistory(int limit, IUser user) {
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

    public Uni<Void> generateBotResponse(String userMessage, Consumer<String> chunkHandler, Consumer<String> completionHandler, String connectionId, String stationId, IUser user) {

        MessageParam userMsg = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofString(userMessage))
                .build();

        conversationHistory.add(userMsg);
        trimHistory();

        Uni<RadioStation> stationUni = radioStationService.getBySlugName(stationId);

        return stationUni.flatMap(station -> {
            String radioStationName = station != null && station.getLocalizedName() != null
                    ? station.getLocalizedName().getOrDefault(LanguageCode.en, station.getSlugName())
                    : stationId;

            Uni<AiAgent> agentUni;
            if (station != null && station.getAiAgentId() != null) {
                agentUni = aiAgentService.getById(station.getAiAgentId(), user, LanguageCode.en);
            } else {
                agentUni = Uni.createFrom().item(() -> null);
            }

            return agentUni.onItem().transform(agent -> {
                String djName = agent != null && agent.getName() != null && !agent.getName().isBlank()
                        ? agent.getName() : "DJ";

                String stationSlug = station != null ? String.valueOf(station.getSlugName()) : stationId;
                String stationCountry = station != null && station.getCountry() != null ? station.getCountry().name() : "";
                String stationBitRate = station != null ? String.valueOf(station.getBitRate()) : "";
                String stationStatus = "unknown";
                String stationTz = station != null && station.getTimeZone() != null ? station.getTimeZone().getId() : "";
                String stationDesc = station != null ? String.valueOf(station.getDescription()) : "";
                String hlsUrl = config.getHost() + "/" + stationSlug + "/radio/stream.m3u8";
                String mp3Url = config.getHost() + "/" + stationSlug + "/radio/stream.mp3";
                String mixplaUrl = "https://player.mixpla.io/?radio=" + stationSlug;

                String djLanguages = "";
                String djPrimaryVoices = "";
                String djCopilotName = "";
                if (agent != null) {
                    if (agent.getPreferredLang() != null && !agent.getPreferredLang().isEmpty()) {
                        djLanguages = agent.getPreferredLang().stream()
                                .sorted(java.util.Comparator.comparingDouble(io.kneo.broadcaster.model.aiagent.LanguagePreference::getWeight).reversed())
                                .map(lp -> lp.getCode().name())
                                .reduce((a, b) -> a + "," + b).orElse("");
                    }
                    if (agent.getPrimaryVoice() != null && !agent.getPrimaryVoice().isEmpty()) {
                        djPrimaryVoices = agent.getPrimaryVoice().stream()
                                .map(v -> v.getName() != null ? v.getName() : v.getId())
                                .reduce((a, b) -> a + "," + b).orElse("");
                    }
                    // Optional: fetch copilot name; keep empty if not resolvable quickly
                }

                String renderedPrompt = mainPrompt
                        .replace("{{djName}}", safe(djName))
                        .replace("{{radioStationName}}", safe(radioStationName))
                        .replace("{{radioStationSlug}}", safe(stationSlug))
                        .replace("{{radioStationCountry}}", safe(stationCountry))
                        .replace("{{radioStationBitRate}}", safe(stationBitRate))
                        .replace("{{radioStationStatus}}", safe(stationStatus))
                        .replace("{{radioStationTimeZone}}", safe(stationTz))
                        .replace("{{radioStationDescription}}", safe(stationDesc))
                        .replace("{{radioStationHlsUrl}}", safe(hlsUrl))
                        .replace("{{radioStationMp3Url}}", safe(mp3Url))
                        .replace("{{radioStationMixplaUrl}}", safe(mixplaUrl))
                        .replace("{{djLanguages}}", safe(djLanguages))
                        .replace("{{djPrimaryVoices}}", safe(djPrimaryVoices))
                        .replace("{{djCopilotName}}", safe(djCopilotName));

                // store assistant display name for this connection
                assistantNameByConnectionId.put(connectionId, djName);

                return MessageCreateParams.builder()
                        .maxTokens(1024L)
                        .system(renderedPrompt)
                        .messages(conversationHistory)
                        .model(Model.CLAUDE_3_5_HAIKU_20241022)
                        .addTool(GetStations.toTool())
                        .addTool(SearchBrandSoundFragments.toTool())
                        .addTool(AddToQueueTool.toTool())
                        .build();
            });
        }).flatMap(params ->
                Uni.createFrom().completionStage(() -> anthropicClient.async().messages().create(params))
                        .flatMap(message -> {
                            Optional<ToolUseBlock> toolUse = message.content().stream()
                                    .flatMap(block -> block.toolUse().stream())
                                    .findFirst();

                            if (toolUse.isPresent()) {
                                return handleToolCall(toolUse.get(), chunkHandler, completionHandler, connectionId);
                            } else {
                                return streamResponse(params, chunkHandler, completionHandler, connectionId);
                            }
                        })
        ).runSubscriptionOn(getDefaultWorkerPool());
    }

    private Uni<Void> handleToolCall(ToolUseBlock toolUse,
                                     Consumer<String> chunkHandler,
                                     Consumer<String> completionHandler,
                                     String connectionId) {
        JsonValue inputVal = toolUse._input();
        Optional<Map<String, JsonValue>> maybeObj = inputVal.asObject();
        Map<String, JsonValue> inputMap = maybeObj.orElse(Collections.emptyMap());

        java.util.function.Function<MessageCreateParams, Uni<Void>> streamFn =
                params -> streamResponse(params, chunkHandler, completionHandler, connectionId);

        if ("get_stations".equals(toolUse.name())) {
            return io.kneo.broadcaster.service.chat.tools.GetStationsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, conversationHistory, systemPromptCall2, streamFn
            );
        } else if ("search_brand_sound_fragments".equals(toolUse.name())) {
            return io.kneo.broadcaster.service.chat.tools.SearchBrandSoundFragmentsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, conversationHistory, systemPromptCall2, streamFn
            );
        } else if ("add_to_queue".equals(toolUse.name())) {
            return io.kneo.broadcaster.service.chat.tools.AddToQueueToolHandler.handle(
                    toolUse, inputMap, queueService, conversationHistory, systemPromptCall2, streamFn
            );
        } else {
            return Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
        }
    }

    private Uni<Void> streamResponse(MessageCreateParams params,
                                     Consumer<String> chunkHandler,
                                     Consumer<String> completionHandler,
                                     String connectionId) {

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
                                        String text = delta.text().get().text();
                                        fullResponse.append(text);

                                        if (text.contains("<thinking>")) {
                                            inThinking[0] = true;
                                        }
                                        if (text.contains("</thinking>")) {
                                            inThinking[0] = false;
                                        }

                                        if (!inThinking[0]
                                                && !text.contains("<thinking>")
                                                && !text.contains("</thinking>")) {

                                            JsonObject chunkMessage = new JsonObject()
                                                    .put("type", "chunk")
                                                    .put("content", text)
                                                    .put("connectionId", connectionId);

                                            chunkHandler.accept(chunkMessage.encode());
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        @Override
                        public void onComplete(Optional<Throwable> error) {

                            if (error.isPresent()) {
                                JsonObject errorMessage = new JsonObject()
                                        .put("type", "error")
                                        .put("message", "Bot response failed: " + error.get().getMessage());
                                completionHandler.accept(errorMessage.encode());
                                return;
                            }

                            String responseText = fullResponse.toString()
                                    .replaceAll("(?s)<thinking>.*?</thinking>", "")
                                    .trim();

                            if (!responseText.isEmpty()) {

                                MessageParam assistantResponse = MessageParam.builder()
                                        .role(MessageParam.Role.ASSISTANT)
                                        .content(MessageParam.Content.ofString(responseText))
                                        .build();

                                conversationHistory.add(assistantResponse);
                                trimHistory();

                                String assistantName = java.util.Optional.ofNullable(assistantNameByConnectionId.get(connectionId)).orElse("DJ");
                                JsonObject botMessage = createMessage(
                                        assistantName,
                                        responseText,
                                        System.currentTimeMillis(),
                                        connectionId,
                                        true
                                );

                                chatHistory.add(botMessage);

                                JsonObject completeMessage = new JsonObject()
                                        .put("type", "message")
                                        .put("data", botMessage);

                                completionHandler.accept(completeMessage.encode());
                            }
                        }
                    })
                    .onCompleteFuture();

        }).runSubscriptionOn(getDefaultWorkerPool());
    }

    private void trimHistory() {
        if (conversationHistory.size() > 30) {
            conversationHistory.subList(0, conversationHistory.size() - 30).clear();
        }
    }

    private String safe(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String loadResourceAsString(String resourcePath) {
        InputStream is = ChatService.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalStateException("Resource not found: " + resourcePath);
        }
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read resource: " + resourcePath, e);
        }
    }

    private JsonObject createMessage(String username, String content, long timestamp, String connectionId, boolean isBot) {
        return new JsonObject()
                .put("id", UUID.randomUUID().toString())
                .put("username", username)
                .put("content", content)
                .put("timestamp", timestamp)
                .put("connectionId", connectionId)
                .put("isBot", isBot);
    }
}
