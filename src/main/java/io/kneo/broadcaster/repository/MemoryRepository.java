package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.Memory;
import io.kneo.broadcaster.model.cnst.MemoryType;
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
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class MemoryRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(KneoBroadcasterNameResolver.MEMORY);

    @Inject
    public MemoryRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    public Uni<List<Memory>> getAll(int limit, int offset, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t ";

        if (!includeArchived) {
            sql += "WHERE (t.archived IS NULL OR t.archived = 0)";
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<List<Memory>> getByBrandId(String brand, int limit, int offset) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE brand = $1" +
                (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.preparedQuery(sql)
                .execute(Tuple.of(brand))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Memory> findById(UUID id) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(id);
                });
    }

    public Uni<List<Memory>> findByType(String brand, MemoryType type) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE brand = $1 AND memory_type = $2";
        return client.preparedQuery(sql)
                .execute(Tuple.of(brand, type))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Memory> insert(Memory memory, IUser user) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (reg_date, author, last_mod_date, last_mod_user,brand, memory_type, content, archived) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id";

        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        Tuple params = Tuple.of(nowTime, user.getId(), nowTime, user.getId())
                .addString(memory.getBrand())
                .addString(memory.getMemoryType().toString())
                .addJsonObject(JsonObject.mapFrom(memory.getContent()))
                .addInteger(memory.getArchived());

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(this::findById);
    }

    public Uni<Memory> update(UUID id, Memory memory, IUser user) {
        String sql = "UPDATE " + entityData.getTableName() +
                " SET last_mod_date=$1, last_mod_user=$2, memory_type=$3, content=$4, archived=$5 " +
                "WHERE id=$6";

        Tuple params = Tuple.tuple()
                .addLocalDateTime(LocalDateTime.now())
                .addLong(user.getId())
                .addString(memory.getMemoryType().toString())
                .addJsonObject(JsonObject.mapFrom(memory.getContent()))
                .addInteger(memory.getArchived())
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

    public Uni<Object> deleteByBrand(String brand) {
        String sql = "DELETE FROM " + entityData.getTableName() + " WHERE brand=$1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(brand))
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<Integer> getAllCount(IUser user) {
        return getAllCount(user.getId(), entityData.getTableName(), entityData.getRlsName());
    }

    private Memory from(Row row) {
        Memory memory = new Memory();
        setDefaultFields(memory, row);
        memory.setBrand(row.getString("brand"));
        memory.setMemoryType(MemoryType.valueOf(row.getString("memory_type")));
        memory.setContent(row.getJsonObject("content").mapTo(JsonObject.class));
        memory.setArchived(row.getInteger("archived"));

        return memory;
    }


}