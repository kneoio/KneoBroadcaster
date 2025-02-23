package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.Listener;
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

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.LISTENER;

@ApplicationScoped
public class ListenersRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(LISTENER);

    @Inject
    public ListenersRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    public Uni<List<Listener>> getAll(int limit, int offset) {
        String sql = "SELECT * FROM " + entityData.getTableName() + (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user) {
        return getAllCount(user.getId(), entityData.getTableName(), entityData.getRlsName());
    }

    public Uni<Listener> findById(UUID uuid, Long userID) {
        return client.preparedQuery(String.format(
                        "SELECT theTable.*, rls.* FROM %s theTable JOIN %s rls ON theTable.id = rls.entity_id " +
                                "WHERE rls.reader = $1 AND theTable.id = $2",
                        entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    }
                    LOGGER.warn("No {} found with id: {}, user: {} ", LISTENER, uuid, userID);
                    return null;
                })
                .onItem().ifNull().failWith(new DocumentHasNotFoundException(uuid));
    }

    public Uni<Listener> findByTelegramName(String telegramName, Long userID) {
        return client.preparedQuery(String.format(
                        "SELECT * " +
                                "FROM %s theTable " +
                                "JOIN %s rls ON theTable.id = rls.entity_id " +
                                "JOIN kneobroadcaster__listener_brands fav_brands ON theTable.id = fav_brands.listener_id " +
                                "JOIN _users u ON u.id = theTable.user_id " +
                                "WHERE reader = $1 AND u.telegram_name = $2 ORDER BY fav_brands.rank",
                        entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(userID, telegramName))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        Listener doc = from(row);
                        doc.setRadioStations(List.of(row.getUUID("brand_id")));
                        return doc;
                    }
                    LOGGER.warn("No {} found with telegram name: {}, user: {} ", LISTENER, telegramName, userID);
                    return null;
                })
                .onItem().ifNull().failWith(new DocumentHasNotFoundException(telegramName));
    }


    public Uni<Listener> insert(Listener listener, Long user) {
        LocalDateTime now = LocalDateTime.now();
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (user_id, author, reg_date, last_mod_user, last_mod_date, country, loc_name, nick_name, slug_name, archived) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id";
        Tuple params = Tuple.tuple()
                .addLong(0L)
                .addLong(0L)
                .addLocalDateTime(now)
                .addLong(0L)
                .addLocalDateTime(now)
                .addString("UNK")
                //.add(mapper.valueToTree(Collections.emptyMap()))
                //.add(mapper.valueToTree(listener.getNickName()))
                .addString(listener.getSlugName())
                .addInteger(0);
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(id -> findById(id, user));
    }

    public Uni<Listener> update(UUID id, Listener listener, Long user) {
        LocalDateTime now = LocalDateTime.now();
        String sql = "UPDATE " + entityData.getTableName() +
                " SET nick_name=$1, slug_name=$2, last_mod_user=$3, last_mod_date=$4 " +
                "WHERE id=$5";
        Tuple params = Tuple.tuple()
              //  .add(mapper.valueToTree(listener.getNickName()))
                .addString(listener.getSlugName())
                .addLong(0L)
                .addLocalDateTime(now)
                .addUUID(id);
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToUni(rowSet -> {
                    if (rowSet.rowCount() == 0)
                        throw new DocumentHasNotFoundException(id);
                    return findById(id, user);
                });
    }

    public Uni<Integer> delete(UUID id) {
        String sql = "DELETE FROM " + entityData.getTableName() + " WHERE id=$1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::rowCount);
    }

    private Listener from(Row row) {
        Listener doc = new Listener();
        setDefaultFields(doc, row);
        doc.setId(row.getUUID("id"));
        doc.setUserId(row.getLong("user_id"));
        doc.setCountry(CountryCode.valueOf(row.getString("country")));
        JsonObject localizedNameJson = row.getJsonObject(COLUMN_LOCALIZED_NAME);
        if (localizedNameJson != null) {
            EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
            localizedNameJson.getMap().forEach((key, value) -> localizedName.put(LanguageCode.valueOf(key), (String) value));
            doc.setLocalizedName(localizedName);
        }
        JsonObject nickName = row.getJsonObject("nickname");
        if (nickName != null) {
            EnumMap<LanguageCode, String> localizedNickName = new EnumMap<>(LanguageCode.class);
            nickName.getMap().forEach((key, value) -> localizedNickName.put(LanguageCode.valueOf(key), (String) value));
            doc.setNickName(localizedNickName);
        }
        doc.setSlugName(row.getString("slug_name"));
        doc.setArchived(row.getInteger("archived"));

        return doc;
    }
}
