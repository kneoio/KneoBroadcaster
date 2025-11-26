package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.kneo.broadcaster.dto.aihelper.llmtool.AvailableStationsAiDTO;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.core.localization.LanguageCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class GetStationsToolHandler {

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            AiHelperService aiHelperService,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        String country = inputMap.getOrDefault("country", JsonValue.from("")).toString();

        return aiHelperService.getAllStations(null, country, null, null)
                .flatMap((AvailableStationsAiDTO stationsData) -> {
                    JsonArray stationsJson = new JsonArray();
                    stationsData.getRadioStations().forEach(station -> {
                        JsonObject stationObj = new JsonObject()
                                .put("name", station.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                                .put("slugName", station.getSlugName())
                                .put("country", station.getCountry())
                                .put("status", station.getRadioStationStatus().toString());
                        stationsJson.add(stationObj);
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
                                                    .content(stationsJson.encode())
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
