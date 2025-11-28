package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.RadioService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class RadioStationControlToolHandler extends BaseToolHandler {

    @Inject
    RadioService radioService;

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            RadioService radioService,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        RadioStationControlToolHandler handler = new RadioStationControlToolHandler();
        handler.radioService = radioService;
        String brand = inputMap.getOrDefault("brand", JsonValue.from("")).toString();
        String action = inputMap.getOrDefault("action", JsonValue.from("")).toString();

        if (brand.isEmpty()) {
            JsonObject errorMsg = new JsonObject()
                    .put("type", "message")
                    .put("data", new JsonObject()
                            .put("type", "BOT")
                            .put("content", "Station brand is required to control a station.")
                    );
            chunkHandler.accept(errorMsg.encode());
            return Uni.createFrom().voidItem();
        }

        if (action.isEmpty()) {
            JsonObject errorMsg = new JsonObject()
                    .put("type", "message")
                    .put("data", new JsonObject()
                            .put("type", "BOT")
                            .put("content", "Action is required: 'start' or 'stop'.")
                    );
            chunkHandler.accept(errorMsg.encode());
            return Uni.createFrom().voidItem();
        }

        if (!action.equals("start") && !action.equals("stop")) {
            JsonObject errorMsg = new JsonObject()
                    .put("type", "message")
                    .put("data", new JsonObject()
                            .put("type", "BOT")
                            .put("content", "Invalid action. Must be 'start' or 'stop'.")
                    );
            chunkHandler.accept(errorMsg.encode());
            return Uni.createFrom().voidItem();
        }

        String actionText = action.equals("start") ? "Starting" : "Stopping";
        handler.sendProcessingChunk(chunkHandler, connectionId, actionText + " station: " + brand);

        Uni<RadioStation> operationUni = action.equals("start") 
                ? radioService.initializeStation(brand)
                : radioService.stopStation(brand);

        return operationUni
                .flatMap((RadioStation station) -> {
                    String resultMessage = station != null 
                            ? "Station '" + brand + "' " + action + "ed successfully"
                            : "Failed to " + action + " station '" + brand + "'";
                    
                    handler.sendProcessingChunk(chunkHandler, connectionId, resultMessage);

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, resultMessage, conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    JsonObject msg = new JsonObject()
                            .put("type", "message")
                            .put("data", new JsonObject()
                                    .put("type", "BOT")
                                    .put("content", "Failed to " + action + " station '" + brand + "': " + err.getMessage())
                            );
                    chunkHandler.accept(msg.encode());
                    return Uni.createFrom().voidItem();
                });
    }
}
