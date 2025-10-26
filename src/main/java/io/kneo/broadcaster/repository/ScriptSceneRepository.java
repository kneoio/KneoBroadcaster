package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.ScriptScene;
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
import io.vertx.core.json.JsonArray;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SCRIPT_SCENE;

@ApplicationScoped
public class ScriptSceneRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SCRIPT_SCENE);

    @Inject
    public ScriptSceneRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<ScriptScene>> listByScript(UUID scriptId, int limit, int offset, boolean includeArchived, IUser user) {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = $1 AND t.script_id = $2";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        sql += " ORDER BY t.last_mod_date DESC";
        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), scriptId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> countByScript(UUID scriptId, boolean includeArchived, IUser user) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = $1 AND t.script_id = $2";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), scriptId))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<ScriptScene> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* FROM %s theTable JOIN %s rls ON theTable.id = rls.entity_id WHERE rls.reader = $1 AND theTable.id = $2";
        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }
        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    } else {
                        throw new DocumentHasNotFoundException(id);
                    }
                });
    }

    public Uni<ScriptScene> insert(ScriptScene scene, IUser user) {
        LocalDateTime nowTime = LocalDateTime.now();
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (author, reg_date, last_mod_user, last_mod_date, script_id, type, title, start_time, weekdays) " +
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING id";
        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addUUID(scene.getScriptId())
                .addString(scene.getType())
                .addString(scene.getTitle())
                .addLocalTime(scene.getStartTime())
                .addArrayOfInteger(scene.getWeekdays() != null ? scene.getWeekdays().toArray(new Integer[0]) : null);
        return client.withTransaction(tx ->
                tx.preparedQuery(sql)
                        .execute(params)
                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                        .onItem().transformToUni(id ->
                                insertRLSPermissions(tx, id, entityData, user)
                                        .onItem().transformToUni(ignored -> upsertPrompts(tx, id, scene.getPrompts()))
                                        .onItem().transform(ignored -> id)
                        )
        ).onItem().transformToUni(id -> findById(id, user, true));
    }

    public Uni<ScriptScene> update(UUID id, ScriptScene scene, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }
                    LocalDateTime nowTime = LocalDateTime.now();
                    String sql = "UPDATE " + entityData.getTableName() +
                            " SET type=$1, title=$2, start_time=$3, weekdays=$4, last_mod_user=$5, last_mod_date=$6 WHERE id=$7";
                    Tuple params = Tuple.tuple()
                            .addString(scene.getType())
                            .addString(scene.getTitle())
                            .addLocalTime(scene.getStartTime())
                            .addArrayOfInteger(scene.getWeekdays() != null ? scene.getWeekdays().toArray(new Integer[0]) : null)
                            .addLong(user.getId())
                            .addLocalDateTime(nowTime)
                            .addUUID(id);
                    return client.withTransaction(tx ->
                            tx.preparedQuery(sql)
                                    .execute(params)
                                    .onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return upsertPrompts(tx, id, scene.getPrompts());
                                    })
                    ).onItem().transformToUni(ignored -> findById(id, user, true));
                });
    }

    public Uni<Integer> archive(UUID id, IUser user) {
        return archive(id, entityData, user);
    }

    public Uni<Integer> delete(UUID id, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[1]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have delete permission", user.getUserName(), id));
                    }
                    return client.withTransaction(tx -> {
                        String deletePromptsSql = "DELETE FROM mixpla_script_scene_prompts WHERE script_scene_id = $1";
                        String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                        String deleteEntitySql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());
                        return tx.preparedQuery(deletePromptsSql).execute(Tuple.of(id))
                                .onItem().transformToUni(ignored -> tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id)))
                                .onItem().transformToUni(ignored -> tx.preparedQuery(deleteEntitySql).execute(Tuple.of(id)))
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    private ScriptScene from(Row row) {
        ScriptScene doc = new ScriptScene();
        setDefaultFields(doc, row);
        doc.setScriptId(row.getUUID("script_id"));
        doc.setType(row.getString("type"));
        doc.setTitle(row.getString("title"));
        doc.setArchived(row.getInteger("archived"));
        doc.setStartTime(row.getLocalTime("start_time"));
        Object[] weekdaysArr = row.getArrayOfIntegers("weekdays");
        if (weekdaysArr != null && weekdaysArr.length > 0) {
            List<Integer> weekdays = new ArrayList<>();
            for (Object o : weekdaysArr) {
                weekdays.add((Integer) o);
            }
            doc.setWeekdays(weekdays);
        }
        return doc;
    }

    private Uni<List<UUID>> loadPrompts(UUID sceneId) {
        String sql = "SELECT prompt_id FROM mixpla_script_scene_prompts WHERE script_scene_id = $1 ORDER BY rank ASC";
        return client.preparedQuery(sql)
                .execute(Tuple.of(sceneId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("prompt_id"))
                .collect().asList();
    }

    private Uni<Void> upsertPrompts(io.vertx.mutiny.sqlclient.SqlClient tx, UUID sceneId, List<UUID> prompts) {
        String deleteSql = "DELETE FROM mixpla_script_scene_prompts WHERE script_scene_id = $1";
        if (prompts == null || prompts.isEmpty()) {
            return tx.preparedQuery(deleteSql)
                    .execute(Tuple.of(sceneId))
                    .replaceWithVoid();
        }
        String insertSql = "INSERT INTO mixpla_script_scene_prompts (script_scene_id, prompt_id, rank) VALUES ($1, $2, $3)";
        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(sceneId))
                .chain(() -> {
                    List<Tuple> batches = new ArrayList<>();
                    for (int i = 0; i < prompts.size(); i++) {
                        batches.add(Tuple.of(sceneId, prompts.get(i), i));
                    }
                    return tx.preparedQuery(insertSql).executeBatch(batches);
                })
                .replaceWithVoid();
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}
