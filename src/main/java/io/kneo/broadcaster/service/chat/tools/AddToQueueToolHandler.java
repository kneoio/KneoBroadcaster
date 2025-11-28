package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.agent.ElevenLabsClient;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.model.chat.ChatMessage;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

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

        Map<String, UUID> soundFragments = new HashMap<>();
        if (inputMap.containsKey("soundFragments")) {
            var opt = inputMap.get("soundFragments").asObject();
            if (opt.isPresent()) {
                Map<String, JsonValue> map = (Map<String, JsonValue>) opt.get();
                for (Map.Entry<String, JsonValue> e : map.entrySet()) {
                    try {
                        soundFragments.put("song1", UUID.fromString(e.getValue().toString()));
                    } catch (Exception ignored) {}
                }
            }
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Generating intro ...");

        Integer finalPriority = priority;
        return elevenLabsClient.textToSpeech(textToTTSIntro, djVoiceId, config.getElevenLabsModelId())
                .flatMap(audioBytes -> {
                    try {
                        Path externalUploadsDir = Paths.get(config.getPathForExternalServiceUploads());
                        Files.createDirectories(externalUploadsDir);
                        
                        String fileName = "tts_" + uploadId + ".mp3";
                        Path audioFilePath = externalUploadsDir.resolve(fileName);
                        Files.write(audioFilePath, audioBytes);
                        
                        handler.sendProcessingChunk(chunkHandler, connectionId, "Intro generated, adding to queue...");
                        
                        Map<String, String> filePaths = new HashMap<>();
                        filePaths.put("audio1", audioFilePath.toAbsolutePath().toString());
                        
                        AddToQueueDTO dto = new AddToQueueDTO();
                        dto.setMergingMethod(MergingType.INTRO_SONG);
                        dto.setFilePaths(filePaths);
                        dto.setSoundFragments(soundFragments.isEmpty() ? null : soundFragments);
                        dto.setPriority(finalPriority != null ? finalPriority : INTERRUPT.value());
                        
                        return queueService.addToQueue(brandName, dto, uploadId);
                    } catch (IOException e) {
                        return Uni.createFrom().failure(new RuntimeException("Failed to save TTS audio file: " + e.getMessage(), e));
                    }
                })
                .flatMap(result -> {
                    JsonObject payload = new JsonObject()
                            .put("ok", result)
                            .put("brandName", brandName)
                            .put("textToTTSIntro", textToTTSIntro);

                    handler.sendProcessingChunk(chunkHandler, connectionId, "Song queued successfully!");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                }).onFailure().recoverWithUni(err -> {
                    String errorMessage;
                    if (err instanceof RadioStationException rsException) {
                        if (rsException.getErrorType() == RadioStationException.ErrorType.STATION_NOT_ACTIVE) {
                            errorMessage = "Station '" + brandName + "' is currently offline.";
                        } else {
                            errorMessage = "Station '" + brandName + "' is not available: " + err.getMessage();
                        }
                    } else {
                        errorMessage = "I could not handle your request due to a technical issue: " + err.getMessage();
                    }
                    
                    chunkHandler.accept(ChatMessage.bot(errorMessage, "bot", connectionId).build().toJson());
                    return Uni.createFrom().voidItem();
                });

    }
}
