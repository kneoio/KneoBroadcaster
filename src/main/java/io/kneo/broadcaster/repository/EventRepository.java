package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.model.cnst.EventPriority;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.scheduler.Schedule;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.EVENT;

@ApplicationScoped
public class EventRepository extends AsyncRepository implements SchedulableRepository<Event> {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(EVENT);

    @Inject
    public EventRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Event>> getAll(int limit, int offset, boolean includeArchived, IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        sql += " ORDER BY t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Event> findById(UUID uuid, IUser user, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2";

        if (!includeArchived) {
            sql += " AND (theTable.archived IS NULL OR theTable.archived = 0)";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    } else {
                        LOGGER.warn("No {} found with id: {}, user: {} ", EVENT, uuid, user.getId());
                        throw new DocumentHasNotFoundException(uuid);
                    }
                });
    }

    public Uni<List<Event>> findForBrand(String brandSlugName, int limit, int offset, IUser user, boolean includeArchived) {
        String sql = "SELECT e.* " +
                "FROM " + entityData.getTableName() + " e " +
                "JOIN " + entityData.getRlsName() + " rls ON e.id = rls.entity_id " +
                "WHERE e.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (e.archived IS NULL OR e.archived = 0)";
        }

        sql += " ORDER BY e.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandSlugName, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> findForBrandCount(String brandSlugName, IUser user, boolean includeArchived) {
        String sql = "SELECT COUNT(e.id) " +
                "FROM " + entityData.getTableName() + " e " +
                "JOIN " + entityData.getRlsName() + " rls ON e.id = rls.entity_id " +
                "WHERE e.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (e.archived IS NULL OR e.archived = 0)";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandSlugName, user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Event> insert(Event event, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

                String sql = "INSERT INTO " + entityData.getTableName() +
                        " (author, reg_date, last_mod_user, last_mod_date, brand_id, type, description, priority, archived, scheduler) " +
                        "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id";

                Tuple params = Tuple.tuple()
                        .addLong(user.getId())
                        .addLocalDateTime(nowTime)
                        .addLong(user.getId())
                        .addLocalDateTime(nowTime)
                        .addUUID(event.getBrand())
                        .addString(event.getType().toString())
                        .addString(event.getDescription())
                        .addString(event.getPriority().name())
                        .addInteger(0)
                        .addJsonObject(event.getSchedule() != null ?
                                JsonObject.of("scheduler", JsonObject.mapFrom(event.getSchedule())) : null);

                return client.withTransaction(tx ->
                        tx.preparedQuery(sql)
                                .execute(params)
                                .onFailure().invoke(throwable -> LOGGER.error("Failed to insert event for user: {}", user.getId(), throwable))
                                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                                .onItem().transformToUni(id ->
                                        insertRLSPermissions(tx, id, entityData, user)
                                                .onItem().transform(ignored -> id)
                                )
                ).onItem().transformToUni(id -> findById(id, user, true));
            } catch (Exception e) {
                LOGGER.error("Failed to prepare insert parameters for event, user: {}", user.getId(), e);
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Event> update(UUID id, Event event, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onFailure().invoke(throwable -> LOGGER.error("Failed to check RLS permissions for update event: {} by user: {}", id, user.getId(), throwable))
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException(
                                        "User does not have edit permission", user.getUserName(), id));
                            }

                            LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET brand_id=$1, type=$2, description=$3, priority=$4, scheduler=$5, last_mod_user=$6, last_mod_date=$7 " +
                                    "WHERE id=$8";

                            Tuple params = Tuple.tuple()
                                    .addUUID(event.getBrand())
                                    .addString(event.getType().name())
                                    .addString(event.getDescription())
                                    .addString(event.getPriority().name())
                                    .addJsonObject(event.getSchedule() != null ?
                                            JsonObject.of("scheduler", JsonObject.mapFrom(event.getSchedule())) : null)
                                    .addLong(user.getId())
                                    .addLocalDateTime(nowTime)
                                    .addUUID(id);

                            return client.preparedQuery(sql)
                                    .execute(params)
                                    .onFailure().invoke(throwable -> LOGGER.error("Failed to update event: {} by user: {}", id, user.getId(), throwable))
                                    .onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return findById(id, user, true);
                                    });
                        });
            } catch (Exception e) {
                LOGGER.error("Failed to prepare update parameters for event: {} by user: {}", id, user.getId(), e);
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Integer> archive(UUID id, IUser user) {
        return archive(id, entityData, user);
    }

    public Uni<Integer> delete(UUID id, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[1]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException(
                                "User does not have delete permission", user.getUserName(), id));
                    }

                    return client.withTransaction(tx -> {
                        String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                        String deleteEntitySql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                        return tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id))
                                .onItem().transformToUni(ignored ->
                                        tx.preparedQuery(deleteEntitySql).execute(Tuple.of(id)))
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    @Override
    public Uni<List<Event>> findActiveScheduled() {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                " WHERE archived = 0 AND scheduler IS NOT NULL";

        return client.query(sql)
                .execute()
                .onFailure().invoke(throwable -> LOGGER.error("Failed to retrieve active scheduled events", throwable))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
                .select().where(e -> e.getSchedule() != null && e.getSchedule().isEnabled())
                .collect().asList();
    }

    private Uni<Event> from(Row row) {
        Event doc = new Event();
        setDefaultFields(doc, row);
        doc.setBrand(row.getUUID("brand_id"));
        doc.setType(EventType.valueOf(row.getString("type")));
        doc.setDescription(row.getString("description"));
        doc.setPriority(EventPriority.valueOf(row.getString("priority")));
        doc.setArchived(row.getInteger("archived"));

        JsonObject scheduleJson = row.getJsonObject("scheduler");
        if (scheduleJson != null) {
            try {
                JsonObject scheduleData = scheduleJson.getJsonObject("scheduler");
                if (scheduleData != null) {
                    Schedule schedule = mapper.treeToValue(mapper.valueToTree(scheduleData.getMap()), Schedule.class);
                    doc.setSchedule(schedule);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to parse scheduler JSON for event: {}", row.getUUID("id"), e);
            }
        }

        return Uni.createFrom().item(doc);
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}