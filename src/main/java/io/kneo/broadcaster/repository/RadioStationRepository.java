package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stats.BrandAgentStats;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.kneo.officeframe.cnst.CountryCode;
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
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.BRAND_STATS;
import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.RADIO_STATION;


@ApplicationScoped
public class RadioStationRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(RADIO_STATION);
    private static final EntityData brandStats = KneoBroadcasterNameResolver.create().getEntityNames(BRAND_STATS);

    @Inject
    public RadioStationRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<RadioStation>> getAll(int limit, int offset, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

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
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<RadioStation> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2";

        if (!includeArchived) {
            sql += " AND (theTable.archived IS NULL OR theTable.archived = 0)";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(user.getId(), id))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return Uni.createFrom().item(from(row));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                    }
                });
    }

    public Uni<RadioStation> findById(UUID id, Long userID, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2";

        if (!includeArchived) {
            sql += " AND (theTable.archived IS NULL OR theTable.archived = 0)";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(userID, id))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return Uni.createFrom().item(from(row));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                    }
                });
    }

    /**
     * Finds a radio station by ID without RLS restrictions.
     * This method should only be used for internal operations where RLS checks are not required.
     * For user-facing operations, use findById(UUID, IUser, boolean) instead.
     */
    public Uni<RadioStation> findByIdInternal(UUID id) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(id);
                });
    }

    public Uni<RadioStation> findByBrandName(String name) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE slug_name = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(name))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(name);
                });
    }

    public Uni<RadioStation> insert(RadioStation station, IUser user) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (author, reg_date, last_mod_user, last_mod_date, country, time_zone, managing_mode, color, loc_name, schedule, slug_name, profile_id, ai_agent_id) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13) RETURNING id";

        OffsetDateTime now = OffsetDateTime.now();

        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addOffsetDateTime(now)
                .addLong(user.getId())
                .addOffsetDateTime(now)
                .addString(station.getCountry().name())
                .addString(station.getTimeZone().getId())
                .addString(station.getManagedBy().name())
                .addString(station.getColor())
                .addValue(mapper.valueToTree(station.getLocalizedName()))
                .addValue(station.getSchedule() != null ? mapper.valueToTree(station.getSchedule()) : null)
                .addString(station.getSlugName())
                .addUUID(station.getProfileId())
                .addUUID(station.getAiAgentId());

        return client.withTransaction(tx -> tx.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(id -> {
                    // Insert RLS permissions for the user
                    String readersSql = String.format(
                            "INSERT INTO %s (reader, entity_id, can_edit, can_delete) VALUES ($1, $2, $3, $4)",
                            entityData.getRlsName()
                    );

                    return tx.preparedQuery(readersSql)
                            .execute(Tuple.of(user.getId(), id, true, true))
                            .onItem().transform(ignored -> id);
                })
        ).onItem().transformToUni(id -> findById(id, user, true));
    }

    public Uni<RadioStation> update(UUID id, RadioStation station, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }

                    String sql = "UPDATE " + entityData.getTableName() +
                            " SET country=$1, time_zone=$2, managing_mode=$3, color=$4, loc_name=$5, schedule=$6, " +
                            "slug_name=$7, profile_id=$8, ai_agent_id=$9, last_mod_user=$10, last_mod_date=$11, archived=$12 " +
                            "WHERE id=$13";

                    OffsetDateTime now = OffsetDateTime.now();

                    Tuple params = Tuple.tuple()
                            .addString(station.getCountry().name())
                            .addString(station.getTimeZone().getId())
                            .addString(station.getManagedBy().name())
                            .addString(station.getColor())
                            .addValue(mapper.valueToTree(station.getLocalizedName()))
                            .addValue(station.getSchedule() != null ? mapper.valueToTree(station.getSchedule()) : null)
                            .addString(station.getSlugName())
                            .addUUID(station.getProfileId())
                            .addUUID(station.getAiAgentId())
                            .addLong(user.getId())
                            .addOffsetDateTime(now)
                            .addInteger(station.getArchived())
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
    }

    public Uni<Integer> archive(UUID uuid, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), uuid)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), uuid));
                    }

                    String sql = String.format("UPDATE %s SET archived = 1, last_mod_date = $1, last_mod_user = $2 WHERE id = $3",
                            entityData.getTableName());

                    return client.preparedQuery(sql)
                            .execute(Tuple.of(OffsetDateTime.now(), user.getId(), uuid))
                            .onItem().transform(RowSet::rowCount);
                });
    }

    public Uni<Integer> delete(UUID id, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[1]) { // Check can_delete permission
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have delete permission", user.getUserName(), id));
                    }

                    return client.withTransaction(tx -> {
                        String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                        String deleteDocSql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                        return tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id))
                                .onItem().transformToUni(ignored -> tx.preparedQuery(deleteDocSql).execute(Tuple.of(id)))
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    private RadioStation from(Row row) {
        RadioStation doc = new RadioStation();
        setDefaultFields(doc, row);

        JsonObject localizedNameJson = row.getJsonObject(COLUMN_LOCALIZED_NAME);
        if (localizedNameJson != null) {
            EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
            localizedNameJson.getMap().forEach((key, value) -> localizedName.put(LanguageCode.valueOf(key), (String) value));
            doc.setLocalizedName(localizedName);
        }
        doc.setSlugName(row.getString("slug_name"));
        doc.setArchived(row.getInteger("archived"));
        doc.setCountry(CountryCode.valueOf(row.getString("country")));
        doc.setManagedBy(ManagedBy.valueOf(row.getString("managing_mode")));
        doc.setTimeZone(java.time.ZoneId.of(row.getString("time_zone")));
        doc.setColor(row.getString("color"));
        doc.setDescription(row.getString("description"));

        JsonObject scheduleJson = row.getJsonObject("schedule");
        if (scheduleJson != null) {
            doc.setSchedule(scheduleJson.getMap());
        }

        UUID aiAgentId = row.getUUID("ai_agent_id");
        if (aiAgentId != null) {
            doc.setAiAgentId(aiAgentId);
        }

        UUID profileId = row.getUUID("profile_id");
        if (profileId != null) {
            doc.setProfileId(profileId);
        }

        return doc;
    }

    public Uni<Void> upsertStationAccess(String stationName, String userAgent) {
        String sql = "INSERT INTO " + brandStats.getTableName() + " (station_name, access_count, last_access_time, user_agent) " +
                "VALUES ($1, 1, $2, $3) ON CONFLICT (station_name) DO UPDATE SET access_count = " + brandStats.getTableName() + ".access_count + 1, " +
                "last_access_time = $2, user_agent = $3;";

        return client.preparedQuery(sql)
                .execute(Tuple.of(stationName, OffsetDateTime.now(), userAgent))
                .replaceWithVoid();
    }

    public Uni<BrandAgentStats> findStationStatsByStationName(String stationName) {
        String sql = "SELECT id, station_name, access_count, last_access_time, user_agent FROM " + brandStats.getTableName() + " WHERE station_name = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(stationName))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return fromStatsRow(iterator.next());
                    } else {
                        return null;
                    }
                });
    }

    public Uni<OffsetDateTime> findLastAccessTimeByBrand(String stationName) {
        String sql = "SELECT last_access_time FROM " + brandStats.getTableName() + " WHERE station_name = $1";
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

    private BrandAgentStats fromStatsRow(Row row) {
        if (row == null) {
            return null;
        }
        BrandAgentStats stats = new BrandAgentStats();
        stats.setId(row.getLong("id"));
        stats.setStationName(row.getString("station_name"));
        stats.setAccessCount(row.getLong("access_count"));
        stats.setLastAccessTime(row.getOffsetDateTime("last_access_time"));
        stats.setUserAgent(row.getString("user_agent"));
        return stats;
    }
}