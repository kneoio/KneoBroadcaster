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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ListenerToolHandler extends BaseToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerToolHandler.class);

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
        ListenerToolHandler handler = new ListenerToolHandler();
        String action = inputMap.getOrDefault("action", JsonValue.from("register")).toString().replace("\"", "");

        if ("list_listeners".equals(action)) {
            return handleListListeners(toolUse, inputMap, listenerService, stationSlug, chunkHandler, connectionId, conversationHistory, systemPromptCall2, streamFn, handler);
        }

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
                    String userName = user.getUserName();

                    ListenerDTO dto = new ListenerDTO();
                    dto.setListenerType(String.valueOf(ListenerType.REGULAR));
                    dto.getLocalizedName().put(LanguageCode.en, userName);
                    if (!nickname.isBlank()) {
                        Set<String> nicknames = Arrays.stream(nickname.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toSet());
                        dto.getNickName().put(LanguageCode.en, nicknames);
                    }

                    return listenerService.upsertWithStationSlug(null, dto, stationSlug, ListenerType.REGULAR, SuperUser.build());
                })
                .flatMap(listenerDTO -> {
                    LOGGER.info("[RegisterListener] Registration successful - listenerId: {}, userId: {}, slugName: {}",
                            listenerDTO.getId(), listenerDTO.getUserId(), listenerDTO.getSlugName());

                    String displayName = !nickname.isBlank() ? nickname : listenerDTO.getLocalizedName().get(LanguageCode.en);
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

    private static Uni<Void> handleListListeners(
            ToolUseBlock toolUse,
            Map<String, JsonValue> inputMap,
            ListenerService listenerService,
            String stationSlug,
            Consumer<String> chunkHandler,
            String connectionId,
            List<MessageParam> conversationHistory,
            String systemPromptCall2,
            Function<MessageCreateParams, Uni<Void>> streamFn,
            ListenerToolHandler handler
    ) {
        String searchTerm = inputMap.getOrDefault("search_term", JsonValue.from("")).toString().replace("\"", "");
        String listenerTypeStr = inputMap.getOrDefault("listener_type", JsonValue.from("")).toString().replace("\"", "");
        
        ListenerType listenerType = null;
        if (listenerTypeStr != null && !listenerTypeStr.isEmpty()) {
            try {
                listenerType = ListenerType.valueOf(listenerTypeStr);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid listener type: {}", listenerTypeStr);
            }
        }

        LOGGER.info("[ListListeners] Searching listeners - searchTerm: {}, listenerType: {}, stationSlug: {}, connectionId: {}",
                searchTerm, listenerType, stationSlug, connectionId);

        handler.sendProcessingChunk(chunkHandler, connectionId, "Searching listeners...");

        String finalSearchTerm = searchTerm.isEmpty() ? null : searchTerm;
        ListenerType finalListenerType = listenerType;

        return listenerService.getBrandListeners(stationSlug, 100, 0, SuperUser.build(), null)
                .map(brandListeners -> brandListeners.stream()
                        .map(bl -> bl.getListenerDTO())
                        .collect(java.util.stream.Collectors.toList()))
                .map(allListeners -> {
                    List<ListenerDTO> filtered = allListeners.stream()
                            .filter(listener -> listener.getArchived() == 0)
                            .filter(listener -> {
                                if (finalListenerType != null) {
                                    String type = listener.getListenerType();
                                    return type != null && type.equals(finalListenerType.name());
                                }
                                return true;
                            })
                            .filter(listener -> {
                                if (finalSearchTerm == null) {
                                    return true;
                                }
                                String term = finalSearchTerm.toLowerCase();
                                
                                if (listener.getEmail() != null && listener.getEmail().toLowerCase().contains(term)) {
                                    return true;
                                }
                                if (listener.getTelegramName() != null && listener.getTelegramName().toLowerCase().contains(term)) {
                                    return true;
                                }
                                if (listener.getSlugName() != null && listener.getSlugName().toLowerCase().contains(term)) {
                                    return true;
                                }
                                if (listener.getLocalizedName() != null) {
                                    for (String name : listener.getLocalizedName().values()) {
                                        if (name != null && name.toLowerCase().contains(term)) {
                                            return true;
                                        }
                                    }
                                }
                                if (listener.getNickName() != null) {
                                    for (Set<String> nicknames : listener.getNickName().values()) {
                                        if (nicknames != null) {
                                            for (String nickname : nicknames) {
                                                if (nickname != null && nickname.toLowerCase().contains(term)) {
                                                    return true;
                                                }
                                            }
                                        }
                                    }
                                }
                                return false;
                            })
                            .toList();
                    
                    return filtered;
                })
                .flatMap(listeners -> {
                    int count = listeners.size();
                    handler.sendProcessingChunk(chunkHandler, connectionId, "Found " + count + " listener" + (count != 1 ? "s" : ""));

                    JsonArray listenersJson = new JsonArray();
                    listeners.forEach(listener -> {
                        JsonObject listenerObj = new JsonObject()
                                .put("id", listener.getId().toString())
                                .put("email", listener.getEmail())
                                .put("slugName", listener.getSlugName())
                                .put("listenerType", listener.getListenerType())
                                .put("country", listener.getCountry());
                        
                        if (listener.getNickName() != null && !listener.getNickName().isEmpty()) {
                            Set<String> nicknames = listener.getNickName().get(LanguageCode.en);
                            if (nicknames != null && !nicknames.isEmpty()) {
                                listenerObj.put("nickname", String.join(", ", nicknames));
                            }
                        }
                        if (listener.getLocalizedName() != null && !listener.getLocalizedName().isEmpty()) {
                            listenerObj.put("name", listener.getLocalizedName().get(LanguageCode.en));
                        }
                        if (listener.getTelegramName() != null) {
                            listenerObj.put("telegramName", listener.getTelegramName());
                        }
                        
                        listenersJson.add(listenerObj);
                    });

                    JsonObject payload = new JsonObject()
                            .put("ok", true)
                            .put("count", count)
                            .put("listeners", listenersJson);

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, payload.encode(), conversationHistory);

                    LOGGER.debug("[ListListeners] Calling follow-up LLM stream");
                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                })
                .onFailure().recoverWithUni(err -> {
                    LOGGER.error("[ListListeners] Failed to list listeners", err);
                    JsonObject errorPayload = new JsonObject()
                            .put("ok", false)
                            .put("error", "Failed to list listeners: " + err.getMessage());

                    handler.addToolUseToHistory(toolUse, conversationHistory);
                    handler.addToolResultToHistory(toolUse, errorPayload.encode(), conversationHistory);

                    MessageCreateParams secondCallParams = handler.buildFollowUpParams(systemPromptCall2, conversationHistory);
                    return streamFn.apply(secondCallParams);
                });
    }
}
