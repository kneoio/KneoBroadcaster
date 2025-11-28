package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.dto.aihelper.LiveContainerDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class GetOnlineStationsToolHandler extends BaseToolHandler {

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            AiHelperService aiHelperService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        GetOnlineStationsToolHandler handler = new GetOnlineStationsToolHandler();

        handler.sendProcessingChunk(chunkHandler, connectionId, "Fetching online stations...");

        List<RadioStationStatus> statuses = Arrays.asList(
                RadioStationStatus.ON_LINE,
                RadioStationStatus.WARMING_UP,
                RadioStationStatus.QUEUE_SATURATED,
                RadioStationStatus.IDLE
        );

        return aiHelperService.getOnline(statuses)
                .flatMap((LiveContainerDTO liveData) -> {
                    int count = liveData.getRadioStations().size();
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Found " + count + " online station" + (count != 1 ? "s" : ""));

                    JsonArray stationsJson = new JsonArray();
                    liveData.getRadioStations().forEach(station -> {
                        JsonObject stationObj = new JsonObject()
                                .put("name", station.getName())
                                .put("slugName", station.getSlugName())
                                .put("status", station.getRadioStationStatus().toString())
                                .put("djName", station.getDjName())
                                .put("info", station.getInfo());
                        stationsJson.add(stationObj);
                    });

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, stationsJson.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    JsonObject msg = new JsonObject()
                            .put("type", "message")
                            .put("data", new JsonObject()
                                    .put("type", "BOT")
                                    .put("content", "I could not handle your request due to a technical issue.")
                            );
                    chunkHandler.accept(msg.encode());
                    return Uni.createFrom().voidItem();
                });
    }
}
