package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.ConversationMemory;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ConversationMemoryRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(KneoBroadcasterNameResolver.MEMORY);

    @Inject
    public ConversationMemoryRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    public Uni<List<ConversationMemory>> getAll(int limit, int offset, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<List<ConversationMemory>> getByBrandId(UUID brandId, int limit, int offset) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE brand_id = $1" +
                (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<ConversationMemory> findById(UUID id) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(id);
                });
    }

    public Uni<ConversationMemory> insert(ConversationMemory memory) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (brand_id, created_date, last_modified, message_type, content, archived) " +
                "VALUES ($1, $2, $3, $4, $5, $6) RETURNING id";

        LocalDateTime now = LocalDateTime.now();
        Tuple params = Tuple.tuple()
                .addUUID(memory.getBrandId())
                .addLocalDateTime(now)
                .addLocalDateTime(now)
                .addString(memory.getMessageType())
                .addJsonObject(JsonObject.mapFrom(memory.getContent()))
                .addBoolean(memory.isArchived());

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(this::findById);
    }

    public Uni<ConversationMemory> update(UUID id, ConversationMemory memory) {
        String sql = "UPDATE " + entityData.getTableName() +
                " SET last_modified=$1, message_type=$2, content=$3, archived=$4 " +
                "WHERE id=$5";

        Tuple params = Tuple.tuple()
                .addLocalDateTime(LocalDateTime.now())
                .addString(memory.getMessageType())
                .addJsonObject(JsonObject.mapFrom(memory.getContent()))
                .addBoolean(memory.isArchived())
                .addUUID(id);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToUni(rowSet -> {
                    if (rowSet.rowCount() == 0) throw new DocumentHasNotFoundException(id);
                    return findById(id);
                });
    }

    public Uni<Integer> delete(UUID id) {
        String sql = "DELETE FROM " + entityData.getTableName() + " WHERE id=$1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<Integer> archive(UUID id) {
        String sql = "UPDATE " + entityData.getTableName() + " SET archived=true WHERE id=$1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<Integer> getAllCount(IUser user) {
        return getAllCount(user.getId(), entityData.getTableName(), entityData.getRlsName());
    }

    private ConversationMemory from(Row row) {
        ConversationMemory memory = new ConversationMemory();
        setDefaultFields(memory, row);
        memory.setBrandId(row.getUUID("brand_id"));
        memory.setMessageType(row.getString("message_type"));
        memory.setContent(row.getJsonObject("content").mapTo(JsonObject.class));
        memory.setArchived(row.getBoolean("archived"));

        return memory;
    }
}