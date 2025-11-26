package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class SearchBrandSoundFragmentsToolHandler {

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            AiHelperService aiHelperService,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        String brandName = inputMap.getOrDefault("brandName", JsonValue.from("")).toString();
        String keyword = inputMap.getOrDefault("keyword", JsonValue.from("")).toString();
        Integer limit = null;
        Integer offset = null;
        try {
            if (inputMap.containsKey("limit")) {
                limit = Integer.parseInt(inputMap.get("limit").toString());
            }
        } catch (Exception ignored) {}
        try {
            if (inputMap.containsKey("offset")) {
                offset = Integer.parseInt(inputMap.get("offset").toString());
            }
        } catch (Exception ignored) {}

        return aiHelperService.searchBrandSoundFragmentsForAi(brandName, keyword, limit, offset)
                .flatMap(list -> {
                    JsonArray items = new JsonArray();
                    list.forEach(f -> {
                        JsonObject obj = new JsonObject()
                                .put("id", String.valueOf(f.getId()))
                                .put("title", f.getTitle())
                                .put("artist", f.getArtist())
                                .put("genres", f.getGenres())
                                .put("labels", f.getLabels())
                                .put("album", f.getAlbum())
                                .put("description", f.getDescription())
                                .put("playedByBrandCount", f.getPlayedByBrandCount())
                                .put("lastTimePlayedByBrand", String.valueOf(f.getLastTimePlayedByBrand()));
                        items.add(obj);
                    });

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
                                                    .content(items.encode())
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
