package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.Tool;
import io.kneo.broadcaster.model.ai.Voice;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
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
    public AiAgentRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    private String getSelectAllQuery() {
        return "SELECT * FROM " + entityData.getTableName();
    }

    public Uni<List<AiAgent>> getAll(int limit, int offset, final IUser user) {
        String sql = getSelectAllQuery() + (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived) {
        String sql = String.format("SELECT COUNT(*) FROM %s t", entityData.getTableName());
        if (!includeArchived) {
            sql += " WHERE (t.archived IS NULL OR t.archived = 0)";
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<AiAgent> findById(UUID id) {
        String sql = getSelectAllQuery() + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(id);
                });
    }

    public Uni<AiAgent> findByName(String name) {
        String sql = getSelectAllQuery() + " WHERE name = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(name))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(name);
                });
    }

    public Uni<List<AiAgent>> findByPreferredLang(LanguageCode lang) {
        String sql = getSelectAllQuery() + " WHERE preferred_lang = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(lang.name()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<AiAgent> insert(AiAgent agent, IUser user) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (author, reg_date, last_mod_user, last_mod_date, archived, name, preferred_lang, main_prompt, preferred_voice, enabled_tools) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id";

        OffsetDateTime now = OffsetDateTime.now();

        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addOffsetDateTime(now)
                .addLong(user.getId())
                .addOffsetDateTime(now)
                .addInteger(agent.getArchived())
                .addString(agent.getName())
                .addString(agent.getPreferredLang().name())
                .addString(agent.getMainPrompt())
                .addValue(agent.getPreferredVoice() != null ? mapper.valueToTree(agent.getPreferredVoice()) : null)
                .addValue(agent.getEnabledTools() != null ? mapper.valueToTree(agent.getEnabledTools()) : null);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(this::findById);
    }

    public Uni<AiAgent> update(UUID id, AiAgent agent, IUser user) {
        String sql = "UPDATE " + entityData.getTableName() +
                " SET last_mod_user=$1, last_mod_date=$2, archived=$3, name=$4, preferred_lang=$5, " +
                "main_prompt=$6, preferred_voice=$7, enabled_tools=$8 " +
                "WHERE id=$9";

        OffsetDateTime now = OffsetDateTime.now();

        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addOffsetDateTime(now)
                .addInteger(agent.getArchived())
                .addString(agent.getName())
                .addString(agent.getPreferredLang().name())
                .addString(agent.getMainPrompt())
                .addValue(agent.getPreferredVoice() != null ? mapper.valueToTree(agent.getPreferredVoice()) : null)
                .addValue(agent.getEnabledTools() != null ? mapper.valueToTree(agent.getEnabledTools()) : null)
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

    public Uni<Integer> softDelete(UUID id, IUser user) {
        String sql = "UPDATE " + entityData.getTableName() +
                " SET archived=true, last_mod_user=$1, last_mod_date=$2 WHERE id=$3";

        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), OffsetDateTime.now(), id))
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<List<AiAgent>> findActiveAgents() {
        String sql = getSelectAllQuery() + " WHERE archived = false";
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    private AiAgent from(Row row) {
        AiAgent agent = new AiAgent();
        setDefaultFields(agent, row);
        agent.setArchived(row.getInteger("archived"));
        agent.setName(row.getString("name"));
        agent.setPreferredLang(LanguageCode.valueOf(row.getString("preferred_lang")));
        agent.setMainPrompt(row.getString("main_prompt"));

        try {
            JsonArray preferredVoiceJson = row.getJsonArray("preferred_voice");
            if (preferredVoiceJson != null) {
                List<Voice> voices = mapper.readValue(preferredVoiceJson.encode(), new TypeReference<List<Voice>>() {});
                agent.setPreferredVoice(voices);
            } else {
                agent.setPreferredVoice(new ArrayList<>());
            }

            JsonArray enabledToolsJson = row.getJsonArray("enabled_tools");
            if (enabledToolsJson != null) {
                List<Tool> tools = mapper.readValue(enabledToolsJson.encode(), new TypeReference<List<Tool>>() {});
                agent.setEnabledTools(tools);
            } else {
                agent.setEnabledTools(new ArrayList<>());
            }

        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to deserialize AI Agent JSONB fields for agent: {}", agent.getName(), e);
            agent.setPreferredVoice(new ArrayList<>());
            agent.setEnabledTools(new ArrayList<>());
        }

        return agent;
    }
}