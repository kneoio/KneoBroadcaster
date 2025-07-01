package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandListener;
import io.kneo.broadcaster.model.Listener;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.LISTENER;

@ApplicationScoped
public class ListenersRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(LISTENER);

    @Inject
    public ListenersRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Listener>> getAll(int limit, int offset, boolean includeArchived, IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        sql += " ORDER BY t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(this::from)
                .concatenate()
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

    public Uni<Listener> findById(UUID uuid, Long userID, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2";

        if (!includeArchived) {
            sql += " AND (theTable.archived IS NULL OR theTable.archived = 0)";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    } else {
                        LOGGER.warn("No {} found with id: {}, user: {} ", LISTENER, uuid, userID);
                        throw new DocumentHasNotFoundException(uuid);
                    }
                });
    }

    public Uni<Listener> findById(UUID uuid, Long userID) {
        return findById(uuid, userID, false);
    }

    public Uni<List<BrandListener>> findForBrand(String slugName, final int limit, final int offset, IUser user, boolean includeArchived) {
        String sql = "SELECT l.* " +
                "FROM " + entityData.getTableName() + " l " +
                "JOIN kneobroadcaster__listener_brands lb ON l.id = lb.listener_id " +
                "JOIN kneobroadcaster__brands b ON b.id = lb.brand_id " +
                "JOIN " + entityData.getRlsName() + " rls ON l.id = rls.entity_id " +
                "WHERE b.slug_name = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (l.archived IS NULL OR l.archived = 0)";
        }

        sql += " ORDER BY l.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(slugName, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Uni<Listener> listenerUni = from(row);
                    return listenerUni.onItem().transform(listener -> {
                        BrandListener brandListener = new BrandListener();
                        brandListener.setId(row.getUUID("id"));
                        brandListener.setListener(listener);
                        return brandListener;
                    });
                })
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> findForBrandCount(String slugName, IUser user, boolean includeArchived) {
        String sql = "SELECT COUNT(l.id) " +
                "FROM " + entityData.getTableName() + " l " +
                "JOIN kneobroadcaster__listener_brands lb ON l.id = lb.listener_id " +
                "JOIN kneobroadcaster__brands b ON b.id = lb.brand_id " +
                "JOIN " + entityData.getRlsName() + " rls ON l.id = rls.entity_id " +
                "WHERE b.slug_name = $1 AND rls.reader = $2";


        if (!includeArchived) {
            sql += " AND (l.archived IS NULL OR l.archived = 0)";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(slugName, user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }


    public Uni<Listener> insert(Listener listener, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

        String sql = "INSERT INTO " + entityData.getTableName() +
                " (user_id, author, reg_date, last_mod_user, last_mod_date, country, loc_name, nickname, slug_name, archived) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id";

        Tuple params = Tuple.tuple()
                .addLong(user.getId())
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addString(listener.getCountry() != null ? listener.getCountry().name() : "UNK")
                .addValue(mapper.valueToTree(listener.getLocalizedName()))
                .addValue(mapper.valueToTree(listener.getNickName()))
                .addString(listener.getSlugName())
                .addInteger(0);

        return client.withTransaction(tx ->
                tx.preparedQuery(sql)
                        .execute(params)
                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                        .onItem().transformToUni(id -> {
                            String rlsSql = String.format(
                                    "INSERT INTO %s (reader, entity_id, can_edit, can_delete) VALUES ($1, $2, $3, $4)",
                                    entityData.getRlsName()
                            );
                            return tx.preparedQuery(rlsSql)
                                    .execute(Tuple.of(user.getId(), id, true, true))
                                    .onItem().transform(ignored -> id);
                        })
        ).onItem().transformToUni(id -> findById(id, user.getId(), true));
    }

    public Uni<Listener> update(UUID id, Listener listener, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException(
                                "User does not have edit permission", user.getUserName(), id));
                    }

                    LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

                    String sql = "UPDATE " + entityData.getTableName() +
                            " SET country=$1, loc_name=$2, nickname=$3, slug_name=$4, last_mod_user=$5, last_mod_date=$6 " +
                            "WHERE id=$7";

                    Tuple params = Tuple.tuple()
                            .addString(listener.getCountry() != null ? listener.getCountry().name() : "UNK")
                            .addValue(mapper.valueToTree(listener.getLocalizedName()))
                            .addValue(mapper.valueToTree(listener.getNickName()))
                            .addString(listener.getSlugName())
                            .addLong(user.getId())
                            .addLocalDateTime(nowTime)
                            .addUUID(id);

                    return client.preparedQuery(sql)
                            .execute(params)
                            .onItem().transformToUni(rowSet -> {
                                if (rowSet.rowCount() == 0) {
                                    return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                }
                                return findById(id, user.getId(), true);
                            });
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
                        String deleteRelatedSql = "DELETE FROM kneobroadcaster__listener_brands WHERE listener_id = $1";
                        String deleteEntitySql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                        return tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id))
                                .onItem().transformToUni(ignored ->
                                        tx.preparedQuery(deleteRelatedSql).execute(Tuple.of(id)))
                                .onItem().transformToUni(ignored ->
                                        tx.preparedQuery(deleteEntitySql).execute(Tuple.of(id)))
                                .onItem().transform(RowSet::rowCount);
                    });
                });
    }

    private Uni<Listener> from(Row row) {
        Listener doc = new Listener();
        setDefaultFields(doc, row);
        doc.setUserId(row.getLong("user_id"));
        doc.setCountry(CountryCode.valueOf(row.getString("country")));

        JsonObject localizedNameJson = row.getJsonObject(COLUMN_LOCALIZED_NAME);
        if (localizedNameJson != null) {
            EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
            localizedNameJson.getMap().forEach((key, value) ->
                    localizedName.put(LanguageCode.valueOf(key), (String) value));
            doc.setLocalizedName(localizedName);
        }

        JsonObject nickName = row.getJsonObject("nickname");
        if (nickName != null) {
            EnumMap<LanguageCode, String> localizedNickName = new EnumMap<>(LanguageCode.class);
            nickName.getMap().forEach((key, value) ->
                    localizedNickName.put(LanguageCode.valueOf(key), (String) value));
            doc.setNickName(localizedNickName);
        }

        doc.setArchived(row.getInteger("archived"));

        return Uni.createFrom().item(doc);
    }
}