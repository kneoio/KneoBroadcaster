package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.service.ListenerService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class ListenerDataToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerDataToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            UserService userService,
            long userId,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        ListenerDataToolHandler handler = new ListenerDataToolHandler();
        String action = inputMap.getOrDefault("action", JsonValue.from("get")).toString().replace("\"", "");
        String fieldName = inputMap.getOrDefault("field_name", JsonValue.from("")).toString().replace("\"", "");
        String fieldValue = inputMap.getOrDefault("field_value", JsonValue.from("")).toString().replace("\"", "");

        if (fieldName.isEmpty()) {
            return handleError(toolUse, "field_name is required", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        LOGGER.info("[ListenerData] Action: {}, fieldName: {}, userId: {}, connectionId: {}",
                action, fieldName, userId, connectionId);

        return listenerService.getAll(1, 0, io.kneo.core.model.user.SuperUser.build())
                .chain(listeners -> {
                    ListenerDTO listener = listeners.stream()
                            .filter(l -> l.getUserId() == userId)
                            .findFirst()
                            .orElse(null);

                    if (listener == null) {
                        return handleError(toolUse, "Listener not found. User must be registered first.", handler, conversationHistory, systemPromptCall2, streamFn);
                    }

                    if ("set".equals(action)) {
                        return handleSet(toolUse, listener, fieldName, fieldValue, listenerService, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                    } else if ("get".equals(action)) {
                        return handleGet(toolUse, listener, fieldName, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                    } else if ("remove".equals(action)) {
                        return handleRemove(toolUse, listener, fieldName, listenerService, handler, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn);
                    } else {
                        return handleError(toolUse, "Invalid action: " + action, handler, conversationHistory, systemPromptCall2, streamFn);
                    }
                });
    }

    private static Uni<Void> handleSet(
            ToolUseBlock toolUse,
            ListenerDTO listener,
            String fieldName,
            String fieldValue,
            ListenerService listenerService,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        if (fieldValue.isEmpty()) {
            return handleError(toolUse, "field_value is required for 'set' action", handler, conversationHistory, systemPromptCall2, streamFn);
        }

        handler.sendProcessingChunk(chunkHandler, connectionId, "Storing user data...");

        if (listener.getUserData() == null) {
            listener.setUserData(new HashMap<>());
        }
        listener.getUserData().put(fieldName, fieldValue);

        return listenerService.upsert(listener.getId().toString(), listener, io.kneo.core.model.user.SuperUser.build())
                .flatMap(updatedListener -> {
                    LOGGER.info("[ListenerData] Set field '{}' = '{}' for listener {}", fieldName, fieldValue, listener.getId());

                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("action", "set")
                            .put("field_name", fieldName)
                            .put("field_value", fieldValue)
                            .put("message", "User data stored successfully");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListenerData] Failed to set field", err);
                    return handleError(toolUse, "Failed to store user data: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleGet(
            ToolUseBlock toolUse,
            ListenerDTO listener,
            String fieldName,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        handler.sendProcessingChunk(chunkHandler, connectionId, "Retrieving user data...");

        String value = null;
        if (listener.getUserData() != null) {
            value = listener.getUserData().get(fieldName);
        }

        JsonObject payload = new JsonObject()
                .put("ok", true)
                .put("action", "get")
                .put("field_name", fieldName)
                .put("field_value", value)
                .put("found", value != null);

        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

        MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(secondCallParams);
    }

    private static Uni<Void> handleRemove(
            ToolUseBlock toolUse,
            ListenerDTO listener,
            String fieldName,
            ListenerService listenerService,
            ListenerDataToolHandler handler,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        handler.sendProcessingChunk(chunkHandler, connectionId, "Removing user data...");

        boolean removed = false;
        if (listener.getUserData() != null) {
            removed = listener.getUserData().remove(fieldName) != null;
        }

        if (!removed) {
            JsonObject payload = new JsonObject()
                    .put("ok", true)
                    .put("action", "remove")
                    .put("field_name", fieldName)
                    .put("removed", false)
                    .put("message", "Field not found");

            handler.addToolUseToHistory(toolUse, conversationHistory);
            handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

            MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
            return streamFn.apply(secondCallParams);
        }

        return listenerService.upsert(listener.getId().toString(), listener, io.kneo.core.model.user.SuperUser.build())
                .flatMap(updatedListener -> {
                    LOGGER.info("[ListenerData] Removed field '{}' for listener {}", fieldName, listener.getId());

                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("action", "remove")
                            .put("field_name", fieldName)
                            .put("removed", true)
                            .put("message", "User data removed successfully");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListenerData] Failed to remove field", err);
                    return handleError(toolUse, "Failed to remove user data: " + err.getMessage(), handler, conversationHistory, systemPromptCall2, streamFn);
                });
    }

    private static Uni<Void> handleError(
            ToolUseBlock toolUse,
            String errorMessage,
            ListenerDataToolHandler handler,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        JsonObject errorPayload = new JsonObject()
                .put("ok", false)
                .put("error", errorMessage);

        handler.addToolUseToHistory(toolUse, conversationHistory);
        handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

        MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
        return streamFn.apply(secondCallParams);
    }
}
