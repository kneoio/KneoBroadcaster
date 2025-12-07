package io.kneo.broadcaster.repository;

import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.chat.ChatMessage;
import io.kneo.broadcaster.model.cnst.ChatType;
import io.kneo.broadcaster.model.cnst.MessageType;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.CHAT_MESSAGE;

@ApplicationScoped
public class ChatRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(CHAT_MESSAGE);

    private final ConcurrentHashMap<String, List<MessageParam>> conversationHistoryCache = new ConcurrentHashMap<>();

    @Inject
    public ChatRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<Void> saveChatMessage(long userId, ChatType chatType, JsonObject message) {
        JsonObject data = message.getJsonObject("data");
        if (data == null) {
            data = message;
        }

        String sql = "INSERT INTO " + entityData.getTableName() + 
                " (id, user_id, chat_type, message_type, username, content, connection_id, timestamp) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";

        UUID id = UUID.randomUUID();
        String messageTypeStr = data.getString("type", MessageType.USER.name());
        MessageType messageType = MessageType.valueOf(messageTypeStr);
        String username = data.getString("username");
        String content = data.getString("content");
        String connectionId = data.getString("connectionId");
        Long timestampMillis = data.getLong("timestamp", System.currentTimeMillis());
        LocalDateTime timestamp = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestampMillis),
                java.time.ZoneOffset.UTC
        );

        return client.preparedQuery(sql)
                .execute(Tuple.of(id, userId, chatType.name(), messageType.name(), username, content)
                        .addString(connectionId)
                        .addLocalDateTime(timestamp))
                .replaceWithVoid()
                .onFailure().invoke(throwable -> 
                    LOGGER.error("Failed to save chat message for user {} and chatType {}", userId, chatType, throwable)
                );
    }

    public Uni<List<JsonObject>> getRecentChatMessages(long userId, ChatType chatType, int limit) {
        String sql = "SELECT * FROM " + entityData.getTableName() + 
                " WHERE user_id = $1 AND chat_type = $2 " +
                "ORDER BY timestamp DESC LIMIT $3";

        return client.preparedQuery(sql)
                .execute(Tuple.of(userId, chatType.name(), limit))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::rowToJsonObject)
                .collect().asList()
                .onItem().transform(list -> {
                    List<JsonObject> reversed = new ArrayList<>(list);
                    java.util.Collections.reverse(reversed);
                    return reversed;
                });
    }

    public List<MessageParam> getConversationHistory(long userId, ChatType chatType) {
        String cacheKey = userId + "_" + chatType.name();
        return conversationHistoryCache.computeIfAbsent(cacheKey, k -> new ArrayList<>());
    }

    public void appendToConversation(long userId, ChatType chatType, MessageParam message) {
        String cacheKey = userId + "_" + chatType.name();
        conversationHistoryCache.computeIfAbsent(cacheKey, k -> new ArrayList<>()).add(message);
    }

    public void clearConversationHistory(long userId, ChatType chatType) {
        String cacheKey = userId + "_" + chatType.name();
        conversationHistoryCache.remove(cacheKey);
    }

    private JsonObject rowToJsonObject(Row row) {
        return new JsonObject()
                .put("type", "message")
                .put("data", new JsonObject()
                        .put("type", row.getString("message_type"))
                        .put("id", row.getUUID("id").toString())
                        .put("username", row.getString("username"))
                        .put("content", row.getString("content"))
                        .put("timestamp", row.getLocalDateTime("timestamp")
                                .atZone(java.time.ZoneOffset.UTC)
                                .toInstant()
                                .toEpochMilli())
                        .put("connectionId", row.getString("connection_id"))
                );
    }

    public Uni<ChatMessage> from(Row row) {
        ChatMessage entity = new ChatMessage();
        entity.setId(row.getUUID("id"));
        entity.setUserId(row.getLong("user_id"));
        entity.setChatType(ChatType.valueOf(row.getString("chat_type")));
        entity.setMessageType(MessageType.valueOf(row.getString("message_type")));
        entity.setUsername(row.getString("username"));
        entity.setContent(row.getString("content"));
        entity.setConnectionId(row.getString("connection_id"));
        entity.setTimestamp(row.getLocalDateTime("timestamp"));
        return Uni.createFrom().item(entity);
    }
}
