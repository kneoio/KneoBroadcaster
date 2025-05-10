package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stats.BrandAgentStats;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
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

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.BRAND_STATS;
import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.RADIO_STATION;

@ApplicationScoped
public class RadioStationRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(RADIO_STATION);
    private static final EntityData brandStats = KneoBroadcasterNameResolver.create().getEntityNames(BRAND_STATS);

    @Inject
    public RadioStationRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    public Uni<List<RadioStation>> getAll(int limit, int offset, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<RadioStation> findById(UUID id) {
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

    public Uni<RadioStation> insert(RadioStation station) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (brand, playlist, created, listeners_count) " +
                "VALUES ($1, $2, $3, $4) RETURNING id";
        Tuple params = Tuple.of(
                mapper.valueToTree(station.getPlaylist()),
                station.getListenersCount()
        );
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(this::findById);
    }

    public Uni<RadioStation> update(UUID id, RadioStation station) {
        String sql = "UPDATE " + entityData.getTableName() +
                " SET brand=$1, playlist=$2, created=$3, listeners_count=$4 " +
                "WHERE id=$5";
        Tuple params = Tuple.of(
                mapper.valueToTree(station.getPlaylist()),
                station.getListenersCount(),
                id
        );
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

    public Uni<Integer> getAllCount(IUser user) {
        return getAllCount(user.getId(), entityData.getTableName(), entityData.getRlsName());
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
        doc.setPrimaryLang(row.getString("primary_lang"));
        doc.setSlugName(row.getString("slug_name"));
        doc.setArchived(row.getInteger("archived"));
        doc.setCountry(CountryCode.valueOf(row.getString("country")));
        doc.setManagedBy(ManagedBy.valueOf(row.getString("managing_mode")));
        JsonObject aiAgentObject = row.getJsonObject("ai_agent");
        doc.setAiAgent(aiAgentObject.mapTo(AiAgent.class));

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
