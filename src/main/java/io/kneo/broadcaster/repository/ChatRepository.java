package io.kneo.broadcaster.repository;

import com.anthropic.models.messages.MessageParam;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ChatRepository {

    private final ConcurrentHashMap<Long, List<JsonObject>> userChatHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<MessageParam>> userConversationHistory = new ConcurrentHashMap<>();

    public void saveChatMessage(long userId, JsonObject message) {
        userChatHistory.computeIfAbsent(userId, id -> new ArrayList<>()).add(message);
    }

    public List<JsonObject> getRecentChatMessages(long userId, int limit) {
        List<JsonObject> list = userChatHistory.computeIfAbsent(userId, id -> new ArrayList<>());
        int start = Math.max(0, list.size() - limit);
        return new ArrayList<>(list.subList(start, list.size()));
    }

    public List<MessageParam> getConversationHistory(long userId) {
        return userConversationHistory.computeIfAbsent(userId, id -> new ArrayList<>());
    }

    public void appendToConversation(long userId, MessageParam message) {
        userConversationHistory.computeIfAbsent(userId, id -> new ArrayList<>()).add(message);
    }
}
