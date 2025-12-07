package io.kneo.broadcaster.service.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.agent.ElevenLabsClient;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.ChatMessageDTO;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.Voice;
import io.kneo.broadcaster.model.cnst.ChatType;
import io.kneo.broadcaster.model.cnst.MessageType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.repository.ChatRepository;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioService;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.util.ResourceUtil;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

public abstract class ChatService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ChatService.class);
    
    protected final AnthropicClient anthropicClient;
    protected final AiHelperService aiHelperService;
    protected final String mainPrompt;
    protected final String followUpPrompt;
    protected final BroadcasterConfig config;
    protected final ConcurrentHashMap<String, String> assistantNameByConnectionId = new ConcurrentHashMap<>();
    
    @Inject
    protected RadioStationService radioStationService;
    @Inject
    protected AiAgentService aiAgentService;
    @Inject
    protected QueueService queueService;
    @Inject
    protected RadioService radioService;
    @Inject
    protected ChatRepository chatRepository;
    @Inject
    protected ElevenLabsClient elevenLabsClient;

    protected ChatService(BroadcasterConfig config, AiHelperService aiHelperService) {
        if (config != null) {
            this.anthropicClient = AnthropicOkHttpClient.builder()
                    .apiKey(config.getAnthropicApiKey())
                    .build();
            this.aiHelperService = aiHelperService;
            this.config = config;
            this.mainPrompt = ResourceUtil.loadResourceAsString("/prompts/mainPrompt.hbs");
            this.followUpPrompt = ResourceUtil.loadResourceAsString("/prompts/followUpPrompt.hbs");
        } else {
            this.anthropicClient = null;
            this.aiHelperService = null;
            this.config = null;
            this.mainPrompt = null;
            this.followUpPrompt = null;
        }
    }

    // Prompt getters to allow subclasses to override prompt sources
    protected String getMainPrompt() {
        return this.mainPrompt;
    }

    protected String getFollowUpPrompt() {
        return this.followUpPrompt;
    }

    protected abstract ChatType getChatType();

    public Uni<String> processUserMessage(String username, String content, String connectionId, String brandName, IUser user) {
        return Uni.createFrom().item(() -> {
            JsonObject message = createMessage(
                    MessageType.USER,
                    username,
                    content,
                    System.currentTimeMillis(),
                    connectionId
            );

            chatRepository.saveChatMessage(user.getId(), brandName, getChatType(), message).subscribe().with(
                    success -> {},
                    failure -> LOGGER.error("Failed to save user message", failure)
            );

            return ChatMessageDTO.user(content, username, connectionId).build().toJson();
        });
    }

    public Uni<String> getChatHistory(String brandName, int limit, IUser user) {
        return chatRepository.getRecentChatMessages(user.getId(), brandName, getChatType(), limit)
                .map(recentMessages -> {

                    JsonArray messages = new JsonArray();
                    recentMessages.forEach(messages::add);

                    JsonObject response = new JsonObject()
                            .put("type", "history")
                            .put("messages", messages);

                    return response.encode();
                });
    }

    public Uni<Void> generateBotResponse(String userMessage, Consumer<String> chunkHandler, Consumer<String> completionHandler, String connectionId, String slugName, IUser user) {

        MessageParam userMsg = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofString(userMessage))
                .build();

        chatRepository.appendToConversation(user.getId(), getChatType(), userMsg);

        Uni<RadioStation> stationUni = radioStationService.getBySlugName(slugName, SuperUser.build()); //I still dont knw shou we use superuser here

        return stationUni.flatMap(station -> {
            String radioStationName = station != null && station.getLocalizedName() != null
                    ? station.getLocalizedName().getOrDefault(LanguageCode.en, station.getSlugName())
                    : slugName;

            Uni<AiAgent> agentUni;
            if (station != null && station.getAiAgentId() != null) {
                agentUni = aiAgentService.getById(station.getAiAgentId(), SuperUser.build(), LanguageCode.en);
            } else {
                agentUni = Uni.createFrom().item(() -> null);
            }

            return agentUni.onItem().transform(agent -> {
                String djName = agent.getName();

                assert station != null;
                String stationSlug = station.getSlugName();
                String stationCountry = station.getCountry().getCountryName();
                String stationBitRate = Long.toString(station.getBitRate());
                String stationStatus = "unknown";
                String stationTz = station.getTimeZone().getId();
                String stationDesc = station.getDescription();
                String hlsUrl = config.getHost() + "/" + stationSlug + "/radio/stream.m3u8";
                String mixplaUrl = "https://player.mixpla.io/?radio=" + stationSlug;

                String djLanguages = "";
                String djPrimaryVoices = "";
                String djCopilotName = "";
                djLanguages = agent.getPreferredLang().stream()
                        .sorted(java.util.Comparator.comparingDouble(io.kneo.broadcaster.model.aiagent.LanguagePreference::getWeight).reversed())
                        .map(lp -> lp.getCode().name())
                        .reduce((a, b) -> a + "," + b).orElse("");
                djPrimaryVoices = agent.getPrimaryVoice().stream()
                        .findFirst()
                        .map(Voice::getId)
                        .orElseThrow(() -> new IllegalStateException("No voice configured for DJ: " + djName));

                String renderedPrompt = getMainPrompt()
                        .replace("{{djName}}", djName)
                        .replace("{{radioStationName}}", radioStationName)
                        .replace("{{owner}}", user.getUserName())
                        .replace("{{radioStationSlug}}", stationSlug)
                        .replace("{{radioStationCountry}}", stationCountry)
                        .replace("{{radioStationBitRate}}", stationBitRate)
                        .replace("{{radioStationStatus}}", stationStatus)
                        .replace("{{radioStationTimeZone}}", stationTz)
                        .replace("{{radioStationDescription}}", stationDesc)
                        .replace("{{radioStationHlsUrl}}", hlsUrl)
                        .replace("{{radioStationMixplaUrl}}", mixplaUrl)
                        .replace("{{djLanguages}}", djLanguages)
                        .replace("{{djCopilotName}}", djCopilotName);

                assistantNameByConnectionId.put(connectionId, djName);
                assistantNameByConnectionId.put(connectionId + "_voice", djPrimaryVoices);

                List<MessageParam> history = chatRepository.getConversationHistory(user.getId(), getChatType());
                return buildMessageCreateParams(renderedPrompt, history);
            });
        }).flatMap(params ->
                Uni.createFrom().completionStage(() -> anthropicClient.async().messages().create(params))
                        .flatMap(message -> {
                            Optional<ToolUseBlock> toolUse = message.content().stream()
                                    .flatMap(block -> block.toolUse().stream())
                                    .findFirst();

                            if (toolUse.isPresent()) {
                                List<MessageParam> history = chatRepository.getConversationHistory(user.getId(), getChatType());
                                return handleToolCall(toolUse.get(), chunkHandler, completionHandler, connectionId, slugName, user.getId(), history);
                            } else {
                                return streamResponse(params, chunkHandler, completionHandler, connectionId, slugName, user.getId());
                            }
                        })
        ).runSubscriptionOn(getDefaultWorkerPool());
    }

    protected abstract MessageCreateParams buildMessageCreateParams(String renderedPrompt, List<MessageParam> history);

    protected abstract List<com.anthropic.models.messages.Tool> getAvailableTools();

    protected abstract Uni<Void> handleToolCall(ToolUseBlock toolUse,
                                                Consumer<String> chunkHandler,
                                                Consumer<String> completionHandler,
                                                String connectionId,
                                                String brandName,
                                                long userId,
                                                List<MessageParam> conversationHistory);

    protected Uni<Void> streamResponse(MessageCreateParams params,
                                     Consumer<String> chunkHandler,
                                     Consumer<String> completionHandler,
                                     String connectionId,
                                     String brandName,
                                     long userId) {

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

                                            chunkHandler.accept(ChatMessageDTO.chunk(text, assistantNameByConnectionId.get(connectionId), connectionId).build().toJson());
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        @Override
                        public void onComplete(Optional<Throwable> error) {

                            if (error.isPresent()) {
                                completionHandler.accept(ChatMessageDTO.error("Bot response failed: " + error.get().getMessage(), "system", "system").build().toJson());
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

                                chatRepository.appendToConversation(userId, getChatType(), assistantResponse);

                                JsonObject botMessage = createMessage(
                                        MessageType.BOT,
                                        assistantNameByConnectionId.get(connectionId),
                                        responseText,
                                        System.currentTimeMillis(),
                                        connectionId
                                );

                                chatRepository.saveChatMessage(userId, brandName, getChatType(), botMessage).subscribe().with(
                                        success -> {},
                                        failure -> LOGGER.error("Failed to save bot message", failure)
                                );


                                String completeMessage = ChatMessageDTO.bot(
                    botMessage.getJsonObject("data").getString("content"),
                    botMessage.getJsonObject("data").getString("username"),
                    botMessage.getJsonObject("data").getString("connectionId")
            )
            .timestamp(botMessage.getJsonObject("data").getLong("timestamp"))
            .build()
            .toJson();


                                completionHandler.accept(completeMessage);
                            }
                        }
                    })
                    .onCompleteFuture();

        }).runSubscriptionOn(getDefaultWorkerPool());
    }

    protected JsonObject createMessage(MessageType type, String username, String content, long timestamp, String connectionId) {
        return new JsonObject()
                .put("type", "message")
                .put("data", new JsonObject()
                        .put("type", type.name())
                        .put("id", UUID.randomUUID().toString())
                        .put("username", username)
                        .put("content", content)
                        .put("timestamp", timestamp)
                        .put("connectionId", connectionId)
                );
    }

    protected Map<String, JsonValue> extractInputMap(ToolUseBlock toolUse) {
        JsonValue inputVal = toolUse._input();
        Optional<Map<String, JsonValue>> maybeObj = inputVal.asObject();
        return maybeObj.orElse(Collections.emptyMap());
    }

    protected java.util.function.Function<MessageCreateParams, Uni<Void>> createStreamFunction(
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {
        return params -> handleFollowUpWithToolDetection(params, chunkHandler, completionHandler, connectionId, brandName, userId);
    }

    protected Uni<Void> handleFollowUpWithToolDetection(
            MessageCreateParams params,
            Consumer<String> chunkHandler,
            Consumer<String> completionHandler,
            String connectionId,
            String brandName,
            long userId) {
        
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(params.maxTokens())
                .system(params.system().orElse(null))
                .messages(params.messages())
                .model(params.model());
        
        for (com.anthropic.models.messages.Tool tool : getAvailableTools()) {
            builder.addTool(tool);
        }
        
        MessageCreateParams paramsWithTools = builder.build();
        
        return Uni.createFrom().completionStage(() -> anthropicClient.async().messages().create(paramsWithTools))
                .flatMap(message -> {
                    Optional<ToolUseBlock> toolUse = message.content().stream()
                            .flatMap(block -> block.toolUse().stream())
                            .findFirst();

                    if (toolUse.isPresent()) {
                        LOGGER.debug("Follow-up detected tool call: {}", toolUse.get().name());
                        List<MessageParam> history = chatRepository.getConversationHistory(userId, getChatType());
                        return handleToolCall(toolUse.get(), chunkHandler, completionHandler, connectionId, brandName, userId, history);
                    } else {
                        return streamResponse(params, chunkHandler, completionHandler, connectionId, brandName, userId);
                    }
                }).runSubscriptionOn(getDefaultWorkerPool());
    }
}
