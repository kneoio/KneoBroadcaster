package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class AddToQueueToolHandler {

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            QueueService queueService,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        String brandName = inputMap.getOrDefault("brandName", JsonValue.from("")).toString();
        String uploadId = inputMap.getOrDefault("uploadId", JsonValue.from("")).toString();
        String mergingMethodStr = inputMap.getOrDefault("mergingMethod", JsonValue.from("NOT_MIXED")).toString();
        Integer priority = null;
        try {
            if (inputMap.containsKey("priority")) {
                priority = Integer.parseInt(inputMap.get("priority").toString());
            }
        } catch (Exception ignored) {}

        Map<String, String> filePaths = new HashMap<>();
        if (inputMap.containsKey("filePaths")) {
            var opt = inputMap.get("filePaths").asObject();
            if (opt.isPresent()) {
              /*  Map<String, JsonValue> map = opt.get();
                for (Map.Entry<String, JsonValue> e : map.entrySet()) {
                    filePaths.put(e.getKey(), e.getValue().toString());
                }*/
            }
        }

        Map<String, UUID> soundFragments = new HashMap<>();
        if (inputMap.containsKey("soundFragments")) {
            var opt = inputMap.get("soundFragments").asObject();
            if (opt.isPresent()) {
                /*Map<String, JsonValue> map = opt.get();
                for (Map.Entry<String, JsonValue> e : map.entrySet()) {
                    try {
                        soundFragments.put(e.getKey(), UUID.fromString(e.getValue().toString()));
                    } catch (Exception ignored) {}
                }*/
            }
        }

        AddToQueueDTO dto = new AddToQueueDTO();
        try {
            dto.setMergingMethod(MergingType.valueOf(mergingMethodStr));
        } catch (Exception e) {
            dto.setMergingMethod(MergingType.NOT_MIXED);
        }
        dto.setFilePaths(filePaths.isEmpty() ? null : filePaths);
        dto.setSoundFragments(soundFragments.isEmpty() ? null : soundFragments);
        if (priority != null) dto.setPriority(priority);

        return queueService.addToQueue(brandName, dto, uploadId)
                .flatMap(result -> {
                    JsonObject payload = new JsonObject()
                            .put("ok", result)
                            .put("brandName", brandName)
                            .put("uploadId", uploadId)
                            .put("mergingMethod", dto.getMergingMethod().name())
                            .put("priority", dto.getPriority());

                    MessageParam assistantToolUseMsg = MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .content(MessageParam.Content.ofBlockParams(
                                    List.of(ContentBlockParam.ofToolUse(
                                            ToolUseBlockParam.builder()
                                                    .name(toolUse.name())
                                                    .id(toolUse.id())
                                                    .input(toolUse._input())
                                                    .build()
                                    ))
                            ))
                            .build();
                    conversationHistory.add(assistantToolUseMsg);

                    MessageParam toolResultMsg = MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(MessageParam.Content.ofBlockParams(
                                    List.of(ContentBlockParam.ofToolResult(
                                            ToolResultBlockParam.builder()
                                                    .toolUseId(toolUse.id())
                                                    .content(payload.encode())
                                                    .build()
                                    ))
                            ))
                            .build();
                    conversationHistory.add(toolResultMsg);

                    MessageCreateParams secondCallParams = MessageCreateParams.builder()
                            .maxTokens(1024L)
                            .system(systemPromptCall2)
                            .messages(conversationHistory)
                            .model(Model.CLAUDE_3_5_HAIKU_20241022)
                            .build();

                    return streamFn.apply(secondCallParams);
                });
    }
}
