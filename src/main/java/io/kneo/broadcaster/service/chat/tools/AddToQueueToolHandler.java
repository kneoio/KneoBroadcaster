package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.agent.ElevenLabsClient;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.kneo.broadcaster.model.cnst.QueuePriority.INTERRUPT;

public class AddToQueueToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AddToQueueToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            QueueService queueService,
            ElevenLabsClient elevenLabsClient,
            BroadcasterConfig config,
            String djVoiceId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        AddToQueueToolHandler handler = new AddToQueueToolHandler();
        String brandName = inputMap.getOrDefault("brandName", JsonValue.from("")).toString();
        String textToTTSIntro = inputMap.getOrDefault("textToTTSIntro", JsonValue.from("")).toString();
        Integer priority = null;
        try {
            if (inputMap.containsKey("priority")) {
                priority = Integer.parseInt(inputMap.get("priority").toString());
            }
        } catch (Exception ignored) {}
        String uploadId = UUID.randomUUID().toString();
        
        LOGGER.info("[AddToQueue] Starting tool execution - uploadId: {}, brandName: {}, connectionId: {}", 
                uploadId, brandName, connectionId);
        LOGGER.debug("[AddToQueue] Tool parameters - textToTTSIntro: '{}', priority: {}", textToTTSIntro, priority);

        Map<String, UUID> soundFragments = new HashMap<>();
        if (inputMap.containsKey("soundFragments")) {
            var opt = inputMap.get("soundFragments").asObject();
            if (opt.isPresent()) {
                Map<String, JsonValue> map = (Map<String, JsonValue>) opt.get();
                for (Map.Entry<String, JsonValue> e : map.entrySet()) {
                    try {
                        UUID fragmentId = UUID.fromString(e.getValue().toString());
                        soundFragments.put("song1", fragmentId);
                        LOGGER.debug("[AddToQueue] Parsed sound fragment - key: {}, id: {}", e.getKey(), fragmentId);
                    } catch (Exception ex) {
                        LOGGER.warn("[AddToQueue] Failed to parse sound fragment UUID: {}", e.getValue(), ex);
                    }
                }
            }
        }
        LOGGER.info("[AddToQueue] Parsed {} sound fragments", soundFragments.size());

        Integer finalPriority = priority;
        
        return queueService.isStationOnline(brandName)
                .flatMap(isOnline -> {
                    if (!isOnline) {
                        LOGGER.warn("[AddToQueue] Station '{}' is offline, cannot queue song", brandName);
                        return Uni.createFrom().failure(
                                new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE,
                                        "Station '" + brandName + "' is currently offline. Please start the station first."));
                    }
                    
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Generating intro ...");
                    LOGGER.info("[AddToQueue] Calling ElevenLabs TTS - voiceId: {}, modelId: {}", djVoiceId, config.getElevenLabsModelId());
                    return elevenLabsClient.textToSpeech(textToTTSIntro, djVoiceId, config.getElevenLabsModelId());
                })
                .flatMap(audioBytes -> {
                    LOGGER.info("[AddToQueue] TTS completed - received {} bytes", audioBytes.length);
                    try {
                        Path externalUploadsDir = Paths.get(config.getPathForExternalServiceUploads());
                        Files.createDirectories(externalUploadsDir);
                        
                        String fileName = "tts_" + uploadId + ".mp3";
                        Path audioFilePath = externalUploadsDir.resolve(fileName);
                        Files.write(audioFilePath, audioBytes);
                        LOGGER.info("[AddToQueue] TTS audio saved to: {}", audioFilePath.toAbsolutePath());
                        
                        handler.sendProcessingChunk(chunkHandler, connectionId, "Intro generated, adding to queue...");
                        
                        Map<String, String> filePaths = new HashMap<>();
                        filePaths.put("audio1", audioFilePath.toAbsolutePath().toString());
                        
                        AddToQueueDTO dto = new AddToQueueDTO();
                        dto.setMergingMethod(MergingType.INTRO_SONG);
                        dto.setFilePaths(filePaths);
                        dto.setSoundFragments(soundFragments.isEmpty() ? null : soundFragments);
                        dto.setPriority(finalPriority != null ? finalPriority : INTERRUPT.value());
                        
                        LOGGER.info("[AddToQueue] Calling queueService.addToQueue - brandName: {}, mergingMethod: {}, priority: {}, soundFragments: {}",
                                brandName, dto.getMergingMethod(), dto.getPriority(), soundFragments.size());
                        return queueService.addToQueue(brandName, dto, uploadId);
                    } catch (IOException e) {
                        LOGGER.error("[AddToQueue] Failed to save TTS audio file", e);
                        return Uni.createFrom().failure(new RuntimeException("Failed to save TTS audio file: " + e.getMessage(), e));
                    }
                })
                .flatMap(result -> {
                    LOGGER.info("[AddToQueue] Queue operation completed successfully - result: {}", result);
                    JsonObject payload = new JsonObject()
                            .put("ok", result)
                            .put("brandName", brandName)
                            .put("textToTTSIntro", textToTTSIntro);

                    handler.sendProcessingChunk(chunkHandler, connectionId, "Song queued successfully!");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    LOGGER.debug("[AddToQueue] Calling follow-up LLM stream for success response");
                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                }).onFailure().recoverWithUni(err -> {
                    LOGGER.error("[AddToQueue] Queue operation failed - uploadId: {}, brandName: {}", uploadId, brandName, err);
                    String errorMessage;
                    if (err instanceof RadioStationException rsException) {
                        if (rsException.getErrorType() == RadioStationException.ErrorType.STATION_NOT_ACTIVE) {
                            errorMessage = "Station '" + brandName + "' is currently offline.";
                            LOGGER.warn("[AddToQueue] Station not active: {}", brandName);
                        } else {
                            errorMessage = "Station '" + brandName + "' is not available: " + err.getMessage();
                            LOGGER.warn("[AddToQueue] Station not available: {} - {}", brandName, err.getMessage());
                        }
                    } else {
                        errorMessage = "I could not handle your request due to a technical issue: " + err.getMessage();
                        LOGGER.error("[AddToQueue] Technical error occurred", err);
                    }
                    
                    JsonObject errorPayload = new JsonObject()
                            .put("ok", false)
                            .put("error", errorMessage)
                            .put("brandName", brandName);
                    
                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);
                    
                    LOGGER.debug("[AddToQueue] Calling follow-up LLM stream for error response");
                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                });

    }
}
