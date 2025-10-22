package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.ai.Prompt;
import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.localization.LanguageCode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.PROMPT;

@ApplicationScoped
public class PromptRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PromptRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(PROMPT);

    @Inject
    public PromptRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Prompt>> getAll(int limit, int offset, boolean includeArchived, final IUser user) {
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
                .onFailure().invoke(throwable -> LOGGER.error("Failed to retrieve prompts for user: {}", user.getId(), throwable))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
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

    public Uni<Prompt> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2";

        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), id))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                    }
                });
    }

    public Uni<Prompt> insert(Prompt prompt, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                String sql = "INSERT INTO " + entityData.getTableName() +
                        " (author, reg_date, last_mod_user, last_mod_date, enabled, prompt, prompt_type, language_code, is_master, locked, title, backup) " +
                        "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) RETURNING id";

                OffsetDateTime now = OffsetDateTime.now();

                Tuple params = Tuple.tuple()
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addBoolean(prompt.isEnabled())
                        .addString(prompt.getPrompt())
                        .addString(prompt.getPromptType().name())
                        .addString(prompt.getLanguageCode().name())
                        .addBoolean(prompt.isMaster())
                        .addBoolean(prompt.isLocked())
                        .addString(prompt.getTitle())
                        .addJsonObject(JsonObject.of("backup", prompt.getBackup()));

                return client.withTransaction(tx ->
                                tx.preparedQuery(sql)
                                        .execute(params)
                                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                                        .onItem().transformToUni(id ->
                                                insertRLSPermissions(tx, id, entityData, user)
                                                        .onItem().transform(ignored -> id)
                                        )
                        )
                        .onItem().transformToUni(id -> findById(id, user, true));
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Prompt> update(UUID id, Prompt prompt, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                            }

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET enabled=$1, prompt=$2, prompt_type=$3, language_code=$4, is_master=$5, locked=$6, title=$7, backup=$8, last_mod_user=$9, last_mod_date=$10 " +
                                    "WHERE id=$11";

                            OffsetDateTime now = OffsetDateTime.now();

                            Tuple params = Tuple.tuple()
                                    .addBoolean(prompt.isEnabled())
                                    .addString(prompt.getPrompt())
                                    .addString(prompt.getPromptType().name())
                                    .addString(prompt.getLanguageCode().name())
                                    .addBoolean(prompt.isMaster())
                                    .addBoolean(prompt.isLocked())
                                    .addString(prompt.getTitle())
                                    .addJsonObject(prompt.getBackup())
                                    .addLong(user.getId())
                                    .addOffsetDateTime(now)
                                    .addUUID(id);

                            return client.preparedQuery(sql)
                                    .execute(params)
                                    .onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return findById(id, user, true);
                                    });
                        });
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    private Prompt from(Row row) {
        Prompt doc = new Prompt();
        setDefaultFields(doc, row);
        doc.setEnabled(row.getBoolean("enabled"));
        doc.setPrompt(row.getString("prompt"));
        doc.setPromptType(PromptType.valueOf(row.getString("prompt_type")));
        doc.setLanguageCode(LanguageCode.valueOf(row.getString("language_code")));
        doc.setMaster(row.getBoolean("is_master"));
        doc.setLocked(row.getBoolean("locked"));
        doc.setTitle(row.getString("title"));
        doc.setBackup(row.getJsonObject("backup"));
        doc.setArchived(row.getInteger("archived"));
        return doc;
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
                        String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                        String deleteDocSql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                        return tx.preparedQuery(deleteRlsSql)
                                .execute(Tuple.of(id))
                                .onItem().transformToUni(ignored ->
                                        tx.preparedQuery(deleteDocSql)
                                                .execute(Tuple.of(id))
                                )
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}
