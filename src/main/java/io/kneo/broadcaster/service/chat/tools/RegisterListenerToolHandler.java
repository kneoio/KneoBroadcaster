package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.model.cnst.ListenerType;
import io.kneo.broadcaster.service.ListenerService;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class RegisterListenerToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterListenerToolHandler.class);

    public static Uni<Void> handle(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            UserService userService,
            long userId,
            String stationSlug,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn
    ) {
        RegisterListenerToolHandler handler = new RegisterListenerToolHandler();
        String nickname = inputMap.getOrDefault("nickname", JsonValue.from("")).toString().replace("\"", "");

        LOGGER.info("[RegisterListener] Starting registration - userId: {}, nickname: {}, stationSlug: {}, connectionId: {}",
                userId, nickname, stationSlug, connectionId);

        handler.sendProcessingChunk(chunkHandler, connectionId, "Registering listener...");

        return userService.findById(userId)
                .chain(userOptional -> {
                    if (userOptional.isEmpty()) {
                        return Uni.createFrom().failure(new IllegalArgumentException("User not found"));
                    }
                    IUser user = userOptional.get();
                    String email = user.getUserName();

                    LOGGER.info("[RegisterListener] Retrieved user email: {}", email);

                    ListenerDTO dto = new ListenerDTO();
                    dto.setEmail(email);
                    dto.setListenerType(String.valueOf(ListenerType.REGULAR));
                    dto.getLocalizedName().put(LanguageCode.en, email);
                    if (nickname != null && !nickname.isBlank()) {
                        dto.getNickName().put(LanguageCode.en, nickname);
                    }

                    return listenerService.upsertWithStationSlug(null, dto, stationSlug, ListenerType.REGULAR, SuperUser.build());
                })
                .flatMap(listenerDTO -> {
                    LOGGER.info("[RegisterListener] Registration successful - listenerId: {}, userId: {}, slugName: {}",
                            listenerDTO.getId(), listenerDTO.getUserId(), listenerDTO.getSlugName());

                    String displayName = nickname != null && !nickname.isBlank() ? nickname : listenerDTO.getLocalizedName().get(LanguageCode.en);
                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("listenerId", String.valueOf(listenerDTO.getId()))
                            .put("userId", listenerDTO.getUserId())
                            .put("slugName", listenerDTO.getSlugName())
                            .put("displayName", displayName);

                    handler.sendProcessingChunk(chunkHandler, connectionId, "Listener registered successfully!");

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    LOGGER.debug("[RegisterListener] Calling follow-up LLM stream for success response");
                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[RegisterListener] Registration failed - userId: {}, stationSlug: {}", userId, stationSlug, err);
                    String errorMessage = "I could not register you due to a technical issue: " + err.getMessage();

                    JsonObject errorPayload = new JsonObject()
                            .put("ok", false)
                            .put("error", errorMessage)
                            .put("userId", userId)
                            .put("stationSlug", stationSlug);

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

                    LOGGER.debug("[RegisterListener] Calling follow-up LLM stream for error response");
                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                });
    }
}
