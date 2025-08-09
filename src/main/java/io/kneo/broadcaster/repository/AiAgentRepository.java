package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.LlmType;
import io.kneo.broadcaster.model.ai.Tool;
import io.kneo.broadcaster.model.ai.Voice;
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
import io.vertx.core.json.JsonArray;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.AI_AGENT;

@ApplicationScoped
public class AiAgentRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiAgentRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(AI_AGENT);

    @Inject
    public AiAgentRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<AiAgent>> getAll(int limit, int offset, boolean includeArchived, IUser user) {
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

    public Uni<AiAgent> findById(UUID uuid, IUser user, boolean includeArchived) {
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
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    } else {
                        LOGGER.warn("No {} found with id: {}, user: {} ", AI_AGENT, uuid, user.getId());
                        throw new DocumentHasNotFoundException(uuid);
                    }
                });
    }

    public Uni<AiAgent> findByName(String name, IUser user) {
        String sql = "SELECT theTable.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.name = $2 AND theTable.archived = 0";

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), name))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    } else {
                        LOGGER.warn("No {} found with name: {}, user: {} ", AI_AGENT, name, user.getId());
                        throw new DocumentHasNotFoundException(name);
                    }
                });
    }

    public Uni<List<AiAgent>> findByPreferredLang(LanguageCode lang, IUser user) {
        String sql = "SELECT theTable.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.preferred_lang = $2 AND theTable.archived = 0";

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), lang.name()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<AiAgent> insert(AiAgent agent, IUser user) {
        OffsetDateTime nowTime = OffsetDateTime.now();

        String sql = "INSERT INTO " + entityData.getTableName() +
                " (author, reg_date, last_mod_user, last_mod_date, name, preferred_lang, llm_type, prompts, " +
                "filler_prompt, preferred_voice, enabled_tools, talkativity) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) RETURNING id";

        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addOffsetDateTime(nowTime)
                .addLong(user.getId())
                .addOffsetDateTime(nowTime)
                .addString(agent.getName())
                .addString(agent.getPreferredLang().name())
                .addString(agent.getLlmType().name())
                .addJsonArray(agent.getPrompts() != null ? JsonArray.of(agent.getPrompts().toArray()) : JsonArray.of())
                .addJsonArray(JsonArray.of(agent.getFillerPrompt().toArray()))
                .addJsonArray(JsonArray.of(agent.getPreferredVoice().toArray()))
                .addJsonArray(JsonArray.of(agent.getEnabledTools().toArray()))
                .addDouble(agent.getTalkativity());

        return client.withTransaction(tx ->
                tx.preparedQuery(sql)
                        .execute(params)
                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                        .onItem().transformToUni(id ->
                                insertRLSPermissions(tx, id, entityData, user)
                                        .onItem().transform(ignored -> id)
                        )
        ).onItem().transformToUni(id -> findById(id, user, true));
    }

    public Uni<AiAgent> update(UUID id, AiAgent agent, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onFailure().invoke(throwable -> LOGGER.error("Failed to check RLS permissions for update ai agent: {} by user: {}", id, user.getId(), throwable))
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException(
                                        "User does not have edit permission", user.getUserName(), id));
                            }

                            OffsetDateTime nowTime = OffsetDateTime.now();

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET last_mod_user=$1, last_mod_date=$2, name=$3, preferred_lang=$4, " +
                                    "llm_type=$5, prompts=$6, filler_prompt=$7, preferred_voice=$8, enabled_tools=$9, talkativity=$10 " +
                                    "WHERE id=$11";

                            Tuple params = Tuple.tuple()
                                    .addLong(user.getId())
                                    .addOffsetDateTime(nowTime)
                                    .addString(agent.getName())
                                    .addString(agent.getPreferredLang().name())
                                    .addString(agent.getLlmType().name())
                                    .addJsonArray(agent.getPrompts() != null ? JsonArray.of(agent.getPrompts().toArray()) : JsonArray.of())
                                    .addJsonArray(JsonArray.of(agent.getFillerPrompt().toArray()))
                                    .addJsonArray(JsonArray.of(agent.getPreferredVoice().toArray()))
                                    .addJsonArray(agent.getEnabledTools() != null ? JsonArray.of(agent.getEnabledTools().toArray()) : JsonArray.of())
                                    .addDouble(agent.getTalkativity())
                                    .addUUID(id);

                            return client.preparedQuery(sql)
                                    .execute(params)
                                    .onFailure().invoke(throwable -> LOGGER.error("Failed to update ai agent: {} by user: {}", id, user.getId(), throwable))
                                    .onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return findById(id, user, true);
                                    });
                        });
            } catch (Exception e) {
                LOGGER.error("Failed to prepare update parameters for ai agent: {} by user: {}", id, user.getId(), e);
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

    public Uni<List<AiAgent>> findActiveAgents(IUser user) {
        String sql = "SELECT theTable.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.archived = 0";

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    private AiAgent from(Row row) {
        AiAgent doc = new AiAgent();
        setDefaultFields(doc, row);
        doc.setArchived(row.getInteger("archived"));
        doc.setName(row.getString("name"));
        doc.setPreferredLang(LanguageCode.valueOf(row.getString("preferred_lang")));
        doc.setLlmType(LlmType.valueOf(row.getString("llm_type")));
        doc.setTalkativity(row.getDouble("talkativity"));

        try {
            JsonArray promptsJson = row.getJsonArray("prompts");
            if (promptsJson != null) {
                List<String> prompts = mapper.readValue(promptsJson.encode(), new TypeReference<List<String>>() {});
                doc.setPrompts(prompts);
            } else {
                doc.setPrompts(new ArrayList<>());
            }
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to deserialize prompts field for agent: {}", doc.getName(), e);
            doc.setPrompts(new ArrayList<>());
        }

        try {
            JsonArray fillerPromptJson = row.getJsonArray("filler_prompt");
            if (fillerPromptJson != null) {
                List<String> prompt = mapper.readValue(fillerPromptJson.encode(), new TypeReference<List<String>>() {});
                doc.setFillerPrompt(prompt);
            } else {
                doc.setFillerPrompt(new ArrayList<>());
            }
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to deserialize filler prompt fields for agent: {}", doc.getName(), e);
            doc.setFillerPrompt(new ArrayList<>());
        }

        try {
            JsonArray preferredVoiceJson = row.getJsonArray("preferred_voice");
            if (preferredVoiceJson != null) {
                List<Voice> voices = mapper.readValue(preferredVoiceJson.encode(), new TypeReference<List<Voice>>() {});
                doc.setPreferredVoice(voices);
            } else {
                doc.setPreferredVoice(new ArrayList<>());
            }

            JsonArray enabledToolsJson = row.getJsonArray("enabled_tools");
            if (enabledToolsJson != null) {
                List<Tool> tools = mapper.readValue(enabledToolsJson.encode(), new TypeReference<List<Tool>>() {});
                doc.setEnabledTools(tools);
            } else {
                doc.setEnabledTools(new ArrayList<>());
            }

        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to deserialize AI Agent JSONB fields for agent: {}", doc.getName(), e);
            doc.setPreferredVoice(new ArrayList<>());
            doc.setEnabledTools(new ArrayList<>());
        }

        return doc;
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}