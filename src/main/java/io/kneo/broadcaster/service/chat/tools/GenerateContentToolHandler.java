package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.live.generated.GeneratedNewsService;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.kneo.broadcaster.model.cnst.QueuePriority.INTERRUPT;

public class GenerateContentToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerateContentToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService,
            GeneratedNewsService generatedNewsService,
            SoundFragmentService soundFragmentService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        GenerateContentToolHandler handler = new GenerateContentToolHandler();
        String brandName = inputMap.getOrDefault("brandName", JsonValue.from("")).toString();
        String promptIdStr = inputMap.getOrDefault("promptId", JsonValue.from("")).toString();
        UUID promptId = UUID.fromString(promptIdStr);

        Integer priority = null;
        try {
            if (inputMap.containsKey("priority")) {
                priority = Integer.parseInt(inputMap.get("priority").toString());
            }
        } catch (Exception ignored) {}
        int finalPriority = priority != null ? priority : INTERRUPT.value();

        handler.sendProcessingChunk(chunkHandler, connectionId, "Generating content for " + brandName + "...");

        return radioStationPool.get(brandName)
                .flatMap(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(
                                new RuntimeException("Station '" + brandName + "' is not online"));
                    }

                    UUID aiAgentId = stream.getAiAgentId();
                    return aiAgentService.getById(aiAgentId, SuperUser.build(), LanguageCode.en)
                            .flatMap(agent -> {
                                handler.sendProcessingChunk(chunkHandler, connectionId, "Checking for existing content...");

                                return generatedNewsService.findOrGenerateFragment(
                                        promptId, agent, stream, null, agent.getPreferredLang().getFirst().getLanguageTag()
                                ).chain(fragment -> {
                                    handler.sendProcessingChunk(chunkHandler, connectionId, 
                                            "Content ready, queueing to air...");
                                    return queueFragmentToAir(fragment, stream, finalPriority, 
                                            soundFragmentService);
                                });
                            });
                })
                .flatMap(result -> {
                    String resultMessage = new JsonObject()
                            .put("ok", result)
                            .put("brandName", brandName)
                            .put("message", "Content generated and queued to air")
                            .encode();

                    handler.sendProcessingChunk(chunkHandler, connectionId, "Content queued successfully!");
                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, resultMessage, conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[GenerateContent] Failed to generate content for brand: {}", brandName, err);

                    JsonObject errorPayload = new JsonObject()
                            .put("ok", false)
                            .put("error", err.getMessage())
                            .put("brandName", brandName);

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                });
    }

    private static Uni<Boolean> queueFragmentToAir(
            SoundFragment fragment,
            IStream stream,
            int priority,
            SoundFragmentService soundFragmentService
    ) {
        fragment.setType(PlaylistItemType.NEWS);
        PlaylistManager playlistManager = stream.getStreamManager().getPlaylistManager();

        return soundFragmentService.getFirstFile(fragment.getId())
                .chain(fileMetadata ->
                        fileMetadata.materializeFileStream(playlistManager.getTempBaseDir())
                                .map(tempFilePath -> fileMetadata)
                ).chain(materializedMetadata -> {
                    fragment.setFileMetadataList(List.of(materializedMetadata));

                    AddToQueueDTO queueDTO = new AddToQueueDTO();
                    queueDTO.setPriority(priority);
                    queueDTO.setMergingMethod(MergingType.NOT_MIXED);

                    return playlistManager.addFragmentToSlice(
                            fragment, priority, stream.getBitRate(), queueDTO
                    );
                });
    }
}
