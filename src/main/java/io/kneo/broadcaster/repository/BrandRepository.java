package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.kneo.officeframe.cnst.CountryCode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.BRAND_STATS;
import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.RADIO_STATION;

@ApplicationScoped
public class BrandRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(RADIO_STATION);
    private static final EntityData brandStats = KneoBroadcasterNameResolver.create().getEntityNames(BRAND_STATS);

    @Inject
    public BrandRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Brand>> getAll(int limit, int offset, boolean includeArchived, final IUser user) {
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
                .onFailure().invoke(throwable -> LOGGER.error("Failed to retrieve radio stations for user: {}", user.getId(), throwable))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<List<Brand>> getAllFiltered(int limit, int offset, boolean includeArchived, final IUser user, String country, String query) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(entityData.getTableName()).append(" t, ")
           .append(entityData.getRlsName()).append(" rls ")
           .append("WHERE t.id = rls.entity_id AND rls.reader = $1");

        int paramIndex = 2;
        if (!includeArchived) {
            sql.append(" AND t.archived = 0");
        }
        if (country != null && !country.isBlank()) {
            sql.append(" AND t.country = $").append(paramIndex++);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (t.search_name LIKE $").append(paramIndex)
               .append(" OR LOWER(t.description) LIKE $").append(paramIndex + 1).append(")");
            paramIndex += 2;
        }
        sql.append(" ORDER BY t.last_mod_date DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
        }

        io.vertx.mutiny.sqlclient.Tuple params = io.vertx.mutiny.sqlclient.Tuple.tuple().addLong(user.getId());
        if (country != null && !country.isBlank()) {
            params.addString(country.toUpperCase());
        }
        if (query != null && !query.isBlank()) {
            String q = "%" + query.toLowerCase() + "%";
            params.addString(q);
            params.addString(q);
        }

        return client.preparedQuery(sql.toString())
                .execute(params)
                .onFailure().invoke(throwable -> LOGGER.error("Failed to retrieve filtered radio stations for user: {}", user.getId(), throwable))
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

    public Uni<Brand> findById(UUID id, IUser user, boolean includeArchived) {
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

    public Uni<Brand> getBySlugName(String name) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE slug_name = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(name))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(name));
                    }
                });
    }

    public Uni<Brand> getBySlugName(String name, IUser user, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.slug_name = $2";

        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), name))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(name));
                    }
                });
    }

    public Uni<Brand> insert(Brand station, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                String sql = "INSERT INTO " + entityData.getTableName() +
                        " (author, reg_date, last_mod_user, last_mod_date, country, time_zone, managing_mode, color, loc_name, ai_overriding, profile_overriding, bit_rate, slug_name, description, profile_id, ai_agent_id, one_time_stream_policy, submission_policy, messaging_policy, title_font, popularity_rate, is_temporary) " +
                        "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22) RETURNING id";

                OffsetDateTime now = OffsetDateTime.now();
                JsonObject localizedNameJson = JsonObject.mapFrom(station.getLocalizedName());
                JsonArray bitRateArray = JsonArray.of(station.getBitRate());

                Tuple params = Tuple.tuple()
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addString(station.getCountry() != null ? station.getCountry().name() : null)
                        .addString(station.getTimeZone().getId())
                        .addString(station.getManagedBy().name())
                        .addString(station.getColor())
                        .addJsonObject(localizedNameJson)
                        .addJsonObject(station.getAiOverriding() != null ? JsonObject.mapFrom(station.getAiOverriding()) : new JsonObject())
                        .addJsonObject(station.getProfileOverriding() != null ? JsonObject.mapFrom(station.getProfileOverriding()) : new JsonObject())
                        .addJsonArray(bitRateArray)
                        .addString(station.getSlugName())
                        .addString(station.getDescription())
                        .addUUID(station.getProfileId())
                        .addUUID(station.getAiAgentId())
                        .addString(station.getOneTimeStreamPolicy().name())
                        .addString(station.getSubmissionPolicy().name())
                        .addString(station.getMessagingPolicy().name())
                        .addString(station.getTitleFont())
                        .addDouble(station.getPopularityRate())
                        .addInteger(station.getIsTemporary());

                return client.withTransaction(tx ->
                                tx.preparedQuery(sql)
                                        .execute(params)
                                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                                        .onItem().transformToUni(id ->
                                                insertRLSPermissions(tx, id, entityData, user)
                                                        .onItem().transformToUni(ignored -> {
                                                            LOGGER.info("Inserting radio station with scripts: {}", station.getScripts());
                                                            if (station.getScripts() != null && !station.getScripts().isEmpty()) {
                                                                LOGGER.info("Calling insertBrandScripts with {} scripts", station.getScripts().size());
                                                                return insertBrandScripts(tx, id, station.getScripts())
                                                                        .onItem().transform(v -> id);
                                                            }
                                                            LOGGER.warn("No scripts to insert for radio station {}", id);
                                                            return Uni.createFrom().item(id);
                                                        })
                                        )
                        )
                        .onItem().transformToUni(id -> findById(id, user, true));
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Brand> update(UUID id, Brand station, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                            }

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET country=$1, time_zone=$2, managing_mode=$3, color=$4, loc_name=$5, ai_overriding=$6, profile_overriding=$7, " +
                                    "bit_rate=$8, slug_name=$9, description=$10, profile_id=$11, ai_agent_id=$12, one_time_stream_policy=$13::submission_policy, submission_policy=$14, messaging_policy=$15, title_font=$16, is_temporary=$17, last_mod_user=$18, last_mod_date=$19 " +
                                    "WHERE id=$20";

                            OffsetDateTime now = OffsetDateTime.now();
                            JsonObject localizedNameJson = JsonObject.mapFrom(station.getLocalizedName());
                            JsonArray bitRateArray = JsonArray.of(station.getBitRate());

                            Tuple params = Tuple.tuple()
                                    .addString(station.getCountry().name())
                                    .addString(station.getTimeZone().getId())
                                    .addString(station.getManagedBy().name())
                                    .addString(station.getColor())
                                    .addJsonObject(localizedNameJson)
                                    .addJsonObject(station.getAiOverriding() != null ? JsonObject.mapFrom(station.getAiOverriding()) : new JsonObject())
                                    .addJsonObject(station.getProfileOverriding() != null ? JsonObject.mapFrom(station.getProfileOverriding()) : new JsonObject())
                                    .addJsonArray(bitRateArray)
                                    .addString(station.getSlugName())
                                    .addString(station.getDescription())
                                    .addUUID(station.getProfileId())
                                    .addUUID(station.getAiAgentId())
                                    .addString(station.getOneTimeStreamPolicy().name())
                                    .addString(station.getSubmissionPolicy().name())
                                    .addString(station.getMessagingPolicy().name())
                                    .addString(station.getTitleFont())
                                    .addInteger(station.getIsTemporary() != null ? station.getIsTemporary() : 0)
                                    .addLong(user.getId())
                                    .addOffsetDateTime(now)
                                    .addUUID(id);

                            return client.withTransaction(tx ->
                                    tx.preparedQuery(sql)
                                            .execute(params)
                                            .onItem().transformToUni(rowSet -> {
                                                if (rowSet.rowCount() == 0) {
                                                    return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                                }
                                                LOGGER.info("Updating radio station {} with scripts: {}", id, station.getScripts());
                                                return deleteBrandScripts(tx, id)
                                                        .onItem().transformToUni(v -> {
                                                            if (station.getScripts() != null && !station.getScripts().isEmpty()) {
                                                                LOGGER.info("Calling insertBrandScripts with {} scripts for update", station.getScripts().size());
                                                                return insertBrandScripts(tx, id, station.getScripts())
                                                                        .onItem().transform(vv -> id);
                                                            }
                                                            LOGGER.warn("No scripts to insert for radio station {} during update", id);
                                                            return Uni.createFrom().item(id);
                                                        });
                                            })
                            ).onItem().transformToUni(stationId -> findById(stationId, user, true));
                        });
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    private Brand from(Row row) {
        Brand doc = new Brand();
        setDefaultFields(doc, row);

        JsonObject localizedNameJson = row.getJsonObject(COLUMN_LOCALIZED_NAME);
        if (localizedNameJson != null) {
            EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
            localizedNameJson.getMap().forEach((key, value) ->
                    localizedName.put(LanguageCode.valueOf(key), (String) value));
            doc.setLocalizedName(localizedName);
        }

        doc.setSlugName(row.getString("slug_name"));
        doc.setArchived(row.getInteger("archived"));
        doc.setIsTemporary(row.getInteger("is_temporary"));
        String country = row.getString("country");
        doc.setCountry(country != null ? CountryCode.valueOf(country) : null);
        doc.setManagedBy(ManagedBy.valueOf(row.getString("managing_mode")));
        doc.setTimeZone(java.time.ZoneId.of(row.getString("time_zone")));
        doc.setColor(row.getString("color"));
        doc.setDescription(row.getString("description"));
        doc.setOneTimeStreamPolicy(SubmissionPolicy.valueOf(row.getString("one_time_stream_policy")));
        doc.setSubmissionPolicy(SubmissionPolicy.valueOf(row.getString("submission_policy")));
        doc.setMessagingPolicy(SubmissionPolicy.valueOf(row.getString("messaging_policy")));
        doc.setTitleFont(row.getString("title_font"));

        JsonArray bitRateJson = row.getJsonArray("bit_rate");
        if (bitRateJson != null && !bitRateJson.isEmpty()) {
            doc.setBitRate(Long.parseLong(bitRateJson.getString(0)));
        } else {
            doc.setBitRate(128000);
        }

        JsonObject aiOverridingJson = row.getJsonObject("ai_overriding");
        if (!aiOverridingJson.isEmpty()) {
            try {
                AiOverriding ai = mapper.treeToValue(
                        mapper.valueToTree(aiOverridingJson.getMap()), AiOverriding.class);
                doc.setAiOverriding(ai);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse ai_overriding JSON for radio station: "
                        + row.getUUID("id"), e);
            }
        }

        JsonObject profileOverridingJson = row.getJsonObject("profile_overriding");
        if (!profileOverridingJson.isEmpty()) {
            try {
                ProfileOverriding profile = mapper.treeToValue(
                        mapper.valueToTree(profileOverridingJson.getMap()), ProfileOverriding.class);
                doc.setProfileOverriding(profile);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse profile_overriding JSON for radio station: "
                        + row.getUUID("id"), e);
            }
        }

        UUID aiAgentId = row.getUUID("ai_agent_id");
        if (aiAgentId != null) {
            doc.setAiAgentId(aiAgentId);
        }
        doc.setPopularityRate(row.getDouble("popularity_rate"));
        UUID profileId = row.getUUID("profile_id");
        if (profileId != null) {
            doc.setProfileId(profileId);
        }

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

    public Uni<Integer> deleteTemporaryBrands(List<String> excludedSlugNames) {
        StringBuilder where = new StringBuilder();
        where.append("is_temporary = 1");

        Tuple params = Tuple.tuple();
        if (excludedSlugNames != null && !excludedSlugNames.isEmpty()) {
            where.append(" AND slug_name NOT IN (");
            for (int i = 0; i < excludedSlugNames.size(); i++) {
                if (i > 0) {
                    where.append(",");
                }
                where.append("$").append(i + 1);
                params.addString(excludedSlugNames.get(i));
            }
            where.append(")");
        }

        String selectIdsSql = "SELECT id FROM " + entityData.getTableName() + " WHERE " + where;

        return client.preparedQuery(selectIdsSql)
                .execute(params)
                .onItem().transformToUni(rows -> {
                    List<UUID> ids = new ArrayList<>();
                    rows.forEach(row -> ids.add(row.getUUID("id")));

                    if (ids.isEmpty()) {
                        return Uni.createFrom().item(0);
                    }

                    UUID[] idArray = ids.toArray(new UUID[0]);
                    Tuple idParam = Tuple.of((Object) idArray);

                    return client.withTransaction(tx -> {
                        String deleteRelationsSql = "DELETE FROM kneobroadcaster__brand_sound_fragments WHERE brand_id = ANY($1)";
                        String deleteBrandsSql = "DELETE FROM " + entityData.getTableName() + " WHERE id = ANY($1)";

                        return tx.preparedQuery(deleteRelationsSql)
                                .execute(idParam)
                                .onItem().transformToUni(ignored -> tx.preparedQuery(deleteBrandsSql).execute(idParam))
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    public Uni<Void> upsertStationAccessWithCountAndGeo(String stationName, Long accessCount, OffsetDateTime lastAccessTime, String userAgent, String ipAddress, String countryCode) {
        String sql = "INSERT INTO " + brandStats.getTableName() +
                " (station_name, access_count, last_access_time, user_agent, ip_address, country_code) " +
                "VALUES ($1, $2, $3, $4, $5, $6) " +
                "ON CONFLICT (station_name, ip_address, country_code) " +
                "DO UPDATE SET access_count = EXCLUDED.access_count + " + brandStats.getTableName() + ".access_count, last_access_time = $3, user_agent = $4;";

        return client.preparedQuery(sql)
                .execute(Tuple.of(stationName, accessCount, lastAccessTime, userAgent, ipAddress, countryCode))
                .replaceWithVoid();
    }

    public Uni<OffsetDateTime> findLastAccessTimeByStationName(String stationName) {
        String sql = "SELECT last_access_time FROM " +
                brandStats.getTableName() + " WHERE station_name = $1 ORDER BY last_access_time DESC LIMIT 1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(stationName))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return iterator.next().getOffsetDateTime("last_access_time");
                    } else {
                        return null;
                    }
                });
    }

    public Uni<List<Brand>> findActiveScheduled() {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE t.archived = 0 AND rls.reader = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(SuperUser.build().getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }

    private Uni<Void> insertBrandScripts(io.vertx.mutiny.sqlclient.SqlClient tx, UUID brandId, List<BrandScriptEntry> scriptEntries) {
        if (scriptEntries == null || scriptEntries.isEmpty()) {
            LOGGER.warn("insertBrandScripts called with null or empty scriptEntries for brand {}", brandId);
            return Uni.createFrom().voidItem();
        }

        LOGGER.info("Inserting {} scripts for brand {}", scriptEntries.size(), brandId);
        String sql = "INSERT INTO kneobroadcaster__brand_scripts (brand_id, script_id, rank, active, user_variables) VALUES ($1, $2, $3, $4, $5)";
        
        List<Uni<Void>> insertOps = scriptEntries.stream()
                .map(entry -> {
                    LOGGER.debug("Inserting script {} for brand {}", entry.getScriptId(), brandId);
                    JsonObject userVarsJson = null;
                    if (entry.getUserVariables() != null && !entry.getUserVariables().isEmpty()) {
                        userVarsJson = new JsonObject(entry.getUserVariables());
                    }
                    return tx.preparedQuery(sql)
                            .execute(Tuple.of(brandId, entry.getScriptId(), 10, true, userVarsJson))
                            .onItem().invoke(() -> LOGGER.info("Successfully inserted script {} for brand {}", entry.getScriptId(), brandId))
                            .onFailure().invoke(t -> LOGGER.error("Failed to insert script {} for brand {}", entry.getScriptId(), brandId, t))
                            .replaceWithVoid();
                })
                .toList();

        return Uni.join().all(insertOps).andFailFast().replaceWithVoid();
    }

    private Uni<Void> deleteBrandScripts(io.vertx.mutiny.sqlclient.SqlClient tx, UUID brandId) {
        LOGGER.info("Deleting all scripts for brand {}", brandId);
        String sql = "DELETE FROM kneobroadcaster__brand_scripts WHERE brand_id = $1";
        return tx.preparedQuery(sql)
                .execute(Tuple.of(brandId))
                .onItem().invoke(rowSet -> LOGGER.info("Deleted {} script entries for brand {}", rowSet.rowCount(), brandId))
                .onFailure().invoke(t -> LOGGER.error("Failed to delete scripts for brand {}", brandId, t))
                .replaceWithVoid();
    }

    public Uni<List<BrandScriptEntry>> getScriptEntriesForBrand(UUID brandId) {
        String sql = "SELECT script_id, user_variables FROM kneobroadcaster__brand_scripts WHERE brand_id = $1 ORDER BY rank";
        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    BrandScriptEntry entry = new BrandScriptEntry();
                    entry.setScriptId(row.getUUID("script_id"));
                    JsonObject userVarsJson = row.getJsonObject("user_variables");
                    if (userVarsJson != null) {
                        entry.setUserVariables(userVarsJson.getMap());
                    }
                    return entry;
                })
                .collect().asList();
    }

    public Uni<List<Row>> getStationStatsByCountry(String stationName, int limit) {
        String sql = "SELECT country_code, SUM(access_count) as total_count " +
                "FROM " + brandStats.getTableName() + " " +
                "WHERE station_name = $1 AND country_code IS NOT NULL " +
                "GROUP BY country_code " +
                "ORDER BY total_count DESC " +
                "LIMIT $2";
        
        return client.preparedQuery(sql)
                .execute(Tuple.of(stationName, limit))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .collect().asList();
    }
}
