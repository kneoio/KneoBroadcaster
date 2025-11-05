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
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SCRIPT_SCENE;

@ApplicationScoped
public class ScriptSceneRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SCRIPT_SCENE);
    private final PromptRepository promptRepository;

    @Inject
    public ScriptSceneRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository, PromptRepository promptRepository) {
        super(client, mapper, rlsRepository);
        this.promptRepository = promptRepository;
    }

    public Uni<List<ScriptScene>> listByScript(UUID scriptId, int limit, int offset, boolean includeArchived, IUser user) {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = $1 AND t.script_id = $2";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        sql += " ORDER BY t.start_time ";
        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), scriptId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList()
                .onItem().transformToUni(scenes -> {
                    if (scenes.isEmpty()) {
                        return Uni.createFrom().item(scenes);
                    }
                    List<Uni<ScriptScene>> sceneUnis = scenes.stream()
                            .map(scene -> promptRepository.getPromptsForScene(scene.getId())
                                    .onItem().transform(promptIds -> {
                                        scene.setPrompts(promptIds);
                                        return scene;
                                    }))
                            .collect(java.util.stream.Collectors.toList());
                    return Uni.join().all(sceneUnis).andFailFast();
                });
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
                })
                .onItem().transformToUni(scene -> 
                    promptRepository.getPromptsForScene(id)
                        .onItem().transform(promptIds -> {
                            scene.setPrompts(promptIds);
                            return scene;
                        })
                );
    }

    public Uni<ScriptScene> insert(ScriptScene scene, IUser user) {
        OffsetDateTime nowTime = OffsetDateTime.now();
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (author, reg_date, last_mod_user, last_mod_date, script_id, title, start_time, one_time_run, weekdays) " +
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING id";
        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addOffsetDateTime(nowTime)
                .addLong(user.getId())
                .addOffsetDateTime(nowTime)
                .addUUID(scene.getScriptId())
                .addString(scene.getTitle())
                .addLocalTime(scene.getStartTime())
                .addBoolean(scene.isOneTimeRun())
                .addArrayOfInteger(scene.getWeekdays() != null ? scene.getWeekdays().toArray(new Integer[0]) : null);
        return client.withTransaction(tx ->
                tx.preparedQuery(sql)
                        .execute(params)
                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                        .onItem().transformToUni(id ->
                                insertRLSPermissions(tx, id, entityData, user)
                                        .onItem().transformToUni(ignored -> promptRepository.updatePromptsForScene(tx, id, scene.getPrompts()))
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
                    OffsetDateTime nowTime = OffsetDateTime.now();
                    String sql = "UPDATE " + entityData.getTableName() +
                            " SET title=$1, start_time=$2, one_time_run=$3, weekdays=$4, last_mod_user=$5, last_mod_date=$6 WHERE id=$7";
                    Tuple params = Tuple.tuple()
                            .addString(scene.getTitle())
                            .addLocalTime(scene.getStartTime())
                            .addBoolean(scene.isOneTimeRun())
                            .addArrayOfInteger(scene.getWeekdays() != null ? scene.getWeekdays().toArray(new Integer[0]) : null)
                            .addLong(user.getId())
                            .addOffsetDateTime(nowTime)
                            .addUUID(id);
                    return client.withTransaction(tx ->
                            tx.preparedQuery(sql)
                                    .execute(params)
                                    .onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return promptRepository.updatePromptsForScene(tx, id, scene.getPrompts());
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
        doc.setTitle(row.getString("title"));
        doc.setArchived(row.getInteger("archived"));
        doc.setStartTime(row.getLocalTime("start_time"));
        doc.setOneTimeRun(row.getBoolean("one_time_run"));
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

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}
