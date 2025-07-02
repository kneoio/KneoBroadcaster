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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class MemoryRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(KneoBroadcasterNameResolver.MEMORY);

    private static final int ARCHIVE_THRESHOLD_HOURS = 24;
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Europe/Lisbon"); // Consistent timezone

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
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE brand = $1 AND memory_type = $2 AND archived = 0";
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

        ZonedDateTime nowTime = ZonedDateTime.now(APPLICATION_ZONE);
        LocalDateTime nowTimeLocal = nowTime.toLocalDateTime();

        Tuple params = Tuple.of(nowTimeLocal, user.getId(), nowTimeLocal, user.getId())
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

        ZonedDateTime nowTime = ZonedDateTime.now(APPLICATION_ZONE);
        LocalDateTime nowTimeLocal = nowTime.toLocalDateTime();

        Tuple params = Tuple.tuple()
                .addLocalDateTime(nowTimeLocal)
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

    public Uni<Integer> patch(String brand, String fragmentTitle, String performer, String content, IUser user) {
        JsonObject newIntroductionItem = new JsonObject()
                .put("fragmentTitle", fragmentTitle)
                .put("performer", performer)
                .put("content", content);

        String selectActiveSql = "SELECT * FROM " + entityData.getTableName() + " WHERE brand = $1 AND archived = 0";

        return client.preparedQuery(selectActiveSql)
                .execute(Tuple.of(brand))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    Memory existingMemory;
                    if (iterator.hasNext()) {
                        existingMemory = from(iterator.next());
                    } else {
                        existingMemory = null;
                    }

                    // Always get current time as ZonedDateTime for consistent calculations
                    ZonedDateTime nowZoned = ZonedDateTime.now(APPLICATION_ZONE);
                    LocalDateTime nowLocalForDb = nowZoned.toLocalDateTime(); // For DB that expects LocalDateTime

                    if (existingMemory != null) {
                        // Assuming existingMemory.getRegDate() now returns ZonedDateTime based on your error
                        ZonedDateTime existingMemoryZoned = existingMemory.getRegDate(); // Directly use it if it's ZonedDateTime

                        long hoursSinceRegistration = ChronoUnit.HOURS.between(existingMemoryZoned, nowZoned);

                        if (hoursSinceRegistration < ARCHIVE_THRESHOLD_HOURS) {
                            JsonObject memoryContent = existingMemory.getContent();
                            if (memoryContent == null) {
                                memoryContent = new JsonObject();
                            }
                            JsonArray introductions = memoryContent.getJsonArray("introductions");
                            if (introductions == null) {
                                introductions = new JsonArray();
                            }
                            introductions.add(newIntroductionItem);
                            memoryContent.put("introductions", introductions);
                            existingMemory.setContent(memoryContent);

                            String updateSql = "UPDATE " + entityData.getTableName() +
                                    " SET last_mod_date=$1, last_mod_user=$2, content=$3 " +
                                    "WHERE id=$4";

                            Tuple params = Tuple.tuple()
                                    .addLocalDateTime(nowLocalForDb) // Pass LocalDateTime to the database
                                    .addLong(user.getId())
                                    .addJsonObject(existingMemory.getContent())
                                    .addUUID(existingMemory.getId());

                            return client.preparedQuery(updateSql)
                                    .execute(params)
                                    .onItem().transform(RowSet::rowCount);

                        } else {
                            return Uni.createFrom().completionStage(
                                    client.preparedQuery("UPDATE " + entityData.getTableName() + " SET archived = 1, last_mod_date = $1, last_mod_user = $2 WHERE id = $3")
                                            .execute(Tuple.of(nowLocalForDb, user.getId(), existingMemory.getId())) // Pass LocalDateTime to the database
                                            .onItem().ignore().andContinueWithNull()
                                            .subscribeAsCompletionStage()
                            ).onItem().transformToUni(v -> {
                                Memory newMemory = new Memory();
                                newMemory.setBrand(brand);
                                newMemory.setMemoryType(MemoryType.CONVERSATION_HISTORY);
                                newMemory.setContent(new JsonObject().put("introductions", new JsonArray().add(newIntroductionItem)));
                                newMemory.setArchived(0);

                                return insert(newMemory, user)
                                        .onItem().transform(insertedMemory -> 1);
                            });
                        }
                    } else {
                        Memory newMemory = new Memory();
                        newMemory.setBrand(brand);
                        newMemory.setMemoryType(MemoryType.CONVERSATION_HISTORY);
                        newMemory.setContent(new JsonObject().put("introductions", new JsonArray().add(newIntroductionItem)));
                        newMemory.setArchived(0);

                        return insert(newMemory, user)
                                .onItem().transform(insertedMemory -> 1);
                    }
                });
    }

    public Uni<Integer> delete(UUID id) {
        String sql = "DELETE FROM " + entityData.getTableName() + " WHERE id=$1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<Integer> deleteByBrand(String brand) {
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
        setDefaultFields(memory, row); // Assumed to set reg_date as ZonedDateTime
        memory.setBrand(row.getString("brand"));
        memory.setMemoryType(MemoryType.valueOf(row.getString("memory_type")));
        memory.setContent(row.getJsonObject("content").mapTo(JsonObject.class));
        memory.setArchived(row.getInteger("archived"));

        return memory;
    }
}