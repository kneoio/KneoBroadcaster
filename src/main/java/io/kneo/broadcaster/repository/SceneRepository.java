package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.repository.prompt.PromptRepository;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SCRIPT_SCENE;

@ApplicationScoped
public class SceneRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SCRIPT_SCENE);
    private final PromptRepository promptRepository;

    @Inject
    public SceneRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository, PromptRepository promptRepository) {
        super(client, mapper, rlsRepository);
        this.promptRepository = promptRepository;
    }

    public Uni<List<Scene>> getAll(int limit, int offset, boolean includeArchived, IUser user, io.kneo.broadcaster.dto.filter.SceneFilterDTO filter) {
        String sql = "SELECT t.*, s.name as script_title FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls, mixpla_scripts s " +
                "WHERE t.id = rls.entity_id AND t.script_id = s.id AND rls.reader = $1";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        if (filter != null && filter.isActivated() && filter.getTimingMode() != null) {
            sql += " AND s.timing_mode = '" + filter.getTimingMode().name() + "'";
        }
        sql += " ORDER BY s.name ASC, t.seq_num ASC, t.start_time ASC";
        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::fromViewEntry)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, io.kneo.broadcaster.dto.filter.SceneFilterDTO filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls, mixpla_scripts s " +
                "WHERE t.id = rls.entity_id AND t.script_id = s.id AND rls.reader = $1";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        if (filter != null && filter.isActivated() && filter.getTimingMode() != null) {
            sql += " AND s.timing_mode = '" + filter.getTimingMode().name() + "'";
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    // Per-script listing
    public Uni<List<Scene>> listByScript(UUID scriptId, int limit, int offset, boolean includeArchived, IUser user) {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = $1 AND t.script_id = $2";
        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }
        sql += " ORDER BY t.seq_num ASC, t.start_time ";
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
                    List<Uni<Scene>> sceneUnis = scenes.stream()
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

    public Uni<Scene> findById(UUID id, IUser user, boolean includeArchived) {
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

    public Uni<Scene> insert(Scene scene, IUser user) {
        OffsetDateTime nowTime = OffsetDateTime.now();
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (author, reg_date, last_mod_user, last_mod_date, script_id, title, start_time, duration_seconds, seq_num, one_time_run, talkativity, weekdays, stage_playlist) " +
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) RETURNING id";
        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addOffsetDateTime(nowTime)
                .addLong(user.getId())
                .addOffsetDateTime(nowTime)
                .addUUID(scene.getScriptId())
                .addString(scene.getTitle())
                .addLocalTime(scene.getStartTime())
                .addInteger(scene.getDurationSeconds())
                .addInteger(scene.getSeqNum())
                .addBoolean(scene.isOneTimeRun())
                .addDouble(scene.getTalkativity())
                .addArrayOfInteger(scene.getWeekdays() != null ? scene.getWeekdays().toArray(new Integer[0]) : null)
                .addJsonObject(scene.getPlaylistRequest() != null ? JsonObject.mapFrom(scene.getPlaylistRequest()) : null);
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

    public Uni<Scene> update(UUID id, Scene scene, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }
                    OffsetDateTime nowTime = OffsetDateTime.now();
                    String sql = "UPDATE " + entityData.getTableName() +
                            " SET title=$1, start_time=$2, duration_seconds=$3, seq_num=$4, one_time_run=$5, talkativity=$6, weekdays=$7, stage_playlist=$8, last_mod_user=$9, last_mod_date=$10 WHERE id=$11";
                    Tuple params = Tuple.tuple()
                            .addString(scene.getTitle())
                            .addLocalTime(scene.getStartTime())
                            .addInteger(scene.getDurationSeconds())
                            .addInteger(scene.getSeqNum())
                            .addBoolean(scene.isOneTimeRun())
                            .addDouble(scene.getTalkativity())
                            .addArrayOfInteger(scene.getWeekdays() != null ? scene.getWeekdays().toArray(new Integer[0]) : null)
                            .addJsonObject(scene.getPlaylistRequest() != null ? JsonObject.mapFrom(scene.getPlaylistRequest()) : null)
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
                        String deletePromptsSql = "DELETE FROM mixpla__script_scene_actions WHERE script_scene_id = $1";
                        String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                        String deleteEntitySql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());
                        return tx.preparedQuery(deletePromptsSql).execute(Tuple.of(id))
                                .onItem().transformToUni(ignored -> tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id)))
                                .onItem().transformToUni(ignored -> tx.preparedQuery(deleteEntitySql).execute(Tuple.of(id)))
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    private Scene fromViewEntry(Row row) {
        Scene doc = from(row);
        String scriptTitle = row.getString("script_title");
        if (scriptTitle != null) {
            doc.setScriptTitle(scriptTitle);
        }
        return doc;
    }

    private Scene from(Row row) {
        Scene doc = new Scene();
        setDefaultFields(doc, row);
        doc.setScriptId(row.getUUID("script_id"));
        doc.setTitle(row.getString("title"));
        doc.setArchived(row.getInteger("archived"));
        doc.setStartTime(row.getLocalTime("start_time"));
        doc.setDurationSeconds(row.getInteger("duration_seconds"));
        Integer seqNum = row.getInteger("seq_num");
        if (seqNum != null) doc.setSeqNum(seqNum);
        doc.setOneTimeRun(row.getBoolean("one_time_run"));
        Double talk = row.getDouble("talkativity");
        if (talk != null) doc.setTalkativity(talk);
        Object[] weekdaysArr = row.getArrayOfIntegers("weekdays");
        if (weekdaysArr != null && weekdaysArr.length > 0) {
            List<Integer> weekdays = new ArrayList<>();
            for (Object o : weekdaysArr) {
                weekdays.add((Integer) o);
            }
            doc.setWeekdays(weekdays);
        }
        JsonObject stagePlaylistJson = row.getJsonObject("stage_playlist");
        if (stagePlaylistJson != null) {
            try {
                PlaylistRequest playlistRequest = mapper.convertValue(stagePlaylistJson.getMap(), PlaylistRequest.class);
                doc.setPlaylistRequest(playlistRequest);
            } catch (Exception e) {
                LOGGER.error("Failed to parse stage_playlist JSON for scene: {}", row.getUUID("id"), e);
            }
        }
        return doc;
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}
