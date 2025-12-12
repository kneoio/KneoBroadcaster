package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.service.RadioService;
import io.smallrye.mutiny.Uni;
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
            handler.sendBotChunk(chunkHandler, connectionId, "bot", "Station brand is required to control a station.");
            return Uni.createFrom().voidItem();
        }

        if (action.isEmpty()) {
            handler.sendBotChunk(chunkHandler, connectionId, "bot", "Action is required: 'start' or 'stop'.");
            return Uni.createFrom().voidItem();
        }

        if (!action.equals("start") && !action.equals("stop")) {
            handler.sendBotChunk(chunkHandler, connectionId, "bot", "Invalid action. Must be 'start' or 'stop'.");
            return Uni.createFrom().voidItem();
        }

        String actionText = action.equals("start") ? "Starting" : "Stopping";
        handler.sendProcessingChunk(chunkHandler, connectionId, actionText + " station: " + brand);

        Uni<Brand> operationUni = action.equals("start")
                ? radioService.initializeStation(brand)
                : radioService.stopStation(brand);

        return operationUni
                .flatMap((Brand station) -> {
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
                    handler.sendBotChunk(chunkHandler, connectionId, "bot", "Failed to " + action + " station '" + brand + "': " + err.getMessage());
                    return Uni.createFrom().voidItem();
                });
    }
}
