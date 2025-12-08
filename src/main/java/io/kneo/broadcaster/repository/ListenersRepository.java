package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandListener;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.model.ListenerFilter;
import io.kneo.broadcaster.model.cnst.ListenerType;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.embedded.DocumentAccessInfo;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.LISTENER;

@ApplicationScoped
public class ListenersRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(LISTENER);

    @Inject
    public ListenersRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Listener>> getAll(int limit, int offset, boolean includeArchived, IUser user) {
        return getAll(limit, offset, includeArchived, user, null);
    }

    public Uni<List<Listener>> getAll(int limit, int offset, boolean includeArchived, IUser user, ListenerFilter filter) {
        String sql = "SELECT t.*, rls.* FROM " + entityData.getTableName() + " t " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
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
        return getAllCount(user, includeArchived, null);
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, ListenerFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Listener> findById(UUID uuid, IUser user, boolean includeArchived) {
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
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    } else {
                        LOGGER.warn("No {} found with id: {}, user: {} ", LISTENER, uuid, user.getId());
                        throw new DocumentHasNotFoundException(uuid);
                    }
                });
    }

    public Uni<List<BrandListener>> findForBrand(String slugName, final int limit, final int offset, IUser user, boolean includeArchived) {
        return findForBrand(slugName, limit, offset, user, includeArchived, null);
    }

    public Uni<List<BrandListener>> findForBrand(String slugName, final int limit, final int offset, IUser user, boolean includeArchived, ListenerFilter filter) {
        String sql = "SELECT l.* " +
                "FROM " + entityData.getTableName() + " l " +
                "JOIN kneobroadcaster__listener_brands lb ON l.id = lb.listener_id " +
                "JOIN kneobroadcaster__brands b ON b.id = lb.brand_id " +
                "JOIN " + entityData.getRlsName() + " rls ON l.id = rls.entity_id " +
                "WHERE b.slug_name = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (l.archived IS NULL OR l.archived = 0)";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter, "l");
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

    public Uni<List<UUID>> getBrandsForListener(UUID listenerId, Long userId) {
        String sql = "SELECT lb.brand_id " +
                "FROM kneobroadcaster__listener_brands lb " +
                "JOIN " + entityData.getRlsName() + " rls ON lb.listener_id = rls.entity_id " +
                "WHERE lb.listener_id = $1 AND rls.reader = $2";

        return client.preparedQuery(sql)
                .execute(Tuple.of(listenerId, userId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("brand_id"))
                .collect().asList();
    }

    public Uni<Integer> findForBrandCount(String slugName, IUser user, boolean includeArchived) {
        return findForBrandCount(slugName, user, includeArchived, null);
    }

    public Uni<Integer> findForBrandCount(String slugName, IUser user, boolean includeArchived, ListenerFilter filter) {
        String sql = "SELECT COUNT(l.id) " +
                "FROM " + entityData.getTableName() + " l " +
                "JOIN kneobroadcaster__listener_brands lb ON l.id = lb.listener_id " +
                "JOIN kneobroadcaster__brands b ON b.id = lb.brand_id " +
                "JOIN " + entityData.getRlsName() + " rls ON l.id = rls.entity_id " +
                "WHERE b.slug_name = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (l.archived IS NULL OR l.archived = 0)";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter, "l");
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(slugName, user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Listener> insert(Listener listener, List<UUID> representedInBrands, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

        String sql = "INSERT INTO " + entityData.getTableName() +
                " (user_id, author, reg_date, last_mod_user, last_mod_date, country, loc_name, nickname, slug_name, telegram_name, listener_type, archived) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) RETURNING id";
        JsonObject localizedNameJson = JsonObject.mapFrom(listener.getLocalizedName());
        JsonObject localizedNickNameJson = JsonObject.mapFrom(listener.getNickName());

        Tuple params = Tuple.tuple()
                .addLong(listener.getUserId())
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addString(listener.getCountry() != null ? listener.getCountry().name() : CountryCode.PT.name())
                .addJsonObject(localizedNameJson)
                .addJsonObject(localizedNickNameJson)
                .addString(listener.getSlugName())
                .addString(listener.getTelegramName())
                .addString(listener.getListenerType() != null ? listener.getListenerType().name() : ListenerType.REGULAR.name())
                .addInteger(0);

        return client.withTransaction(tx ->
                tx.preparedQuery(sql)
                        .execute(params)
                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                        .onItem().transformToUni(id ->
                                insertRLSPermissions(tx, id, entityData, user)
                                        .onItem().transformToUni(ignored -> insertBrandAssociations(tx, id, representedInBrands, user, nowTime))
                                        .onItem().transform(ignored -> id)
                        )
        ).onItem().transformToUni(id -> findById(id, user, true));
    }

    private Uni<Void> insertBrandAssociations(io.vertx.mutiny.sqlclient.SqlClient tx, UUID listenerId, List<UUID> representedInBrands, IUser user, LocalDateTime nowTime) {
        if (representedInBrands == null || representedInBrands.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String insertBrandsSql = "INSERT INTO kneobroadcaster__listener_brands (listener_id, brand_id, reg_date, rank) VALUES ($1, $2, $3, $4)";
        List<Tuple> insertParams = representedInBrands.stream()
                .map(brandId -> Tuple.of(listenerId, brandId, nowTime, 99))
                .collect(Collectors.toList());

        return tx.preparedQuery(insertBrandsSql)
                .executeBatch(insertParams)
                .onItem().ignore().andContinueWithNull();
    }

    public Uni<Listener> update(UUID id, Listener listener, List<UUID> representedInBrands, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onFailure().invoke(throwable -> LOGGER.error("Failed to check RLS permissions for update listener: {} by user: {}", id, user.getId(), throwable))
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(new DocumentModificationAccessException(
                                        "User does not have edit permission", user.getUserName(), id));
                            }

                            LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();
                            JsonObject localizedNameJson = JsonObject.mapFrom(listener.getLocalizedName());
                            JsonObject localizedNickNameJson = JsonObject.mapFrom(listener.getNickName());

                            return client.withTransaction(tx -> {
                                String sql = "UPDATE " + entityData.getTableName() +
                                        " SET country=$1, loc_name=$2, nickname=$3, slug_name=$4, telegram_name=$5, listener_type=COALESCE($6, listener_type), last_mod_user=$7, last_mod_date=$8 " +
                                        "WHERE id=$9";

                                Tuple params = Tuple.tuple()
                                        .addString(listener.getCountry().name())
                                        .addJsonObject(localizedNameJson)
                                        .addJsonObject(localizedNickNameJson)
                                        .addString(listener.getSlugName())
                                        .addString(listener.getTelegramName())
                                        .addString(listener.getListenerType() != null ? listener.getListenerType().name() : null)
                                        .addLong(user.getId())
                                        .addLocalDateTime(nowTime)
                                        .addUUID(id);

                                return tx.preparedQuery(sql)
                                        .execute(params)
                                        .onFailure().invoke(throwable -> LOGGER.error("Failed to update listener: {} by user: {}", id, user.getId(), throwable))
                                        .onItem().transformToUni(rowSet -> {
                                            if (rowSet.rowCount() == 0) {
                                                return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                            }
                                            return updateBrandAssociations(tx, id, representedInBrands, user, nowTime);
                                        });
                            }).onItem().transformToUni(ignored -> findById(id, user, true));
                        });
            } catch (Exception e) {
                LOGGER.error("Failed to prepare update parameters for listener: {} by user: {}", id, user.getId(), e);
                return Uni.createFrom().failure(e);
            }
        });
    }

    private Uni<Void> updateBrandAssociations(io.vertx.mutiny.sqlclient.SqlClient tx, UUID listenerId, List<UUID> representedInBrands, IUser user, LocalDateTime nowTime) {
        if (representedInBrands == null) {
            return Uni.createFrom().voidItem();
        }

        String getCurrentBrandsSql = "SELECT brand_id FROM kneobroadcaster__listener_brands WHERE listener_id = $1";

        return tx.preparedQuery(getCurrentBrandsSql)
                .execute(Tuple.of(listenerId))
                .onItem().transformToUni(currentRows -> {
                    List<UUID> currentBrands = new ArrayList<>();
                    currentRows.forEach(row -> currentBrands.add(row.getUUID("brand_id")));
                    List<UUID> brandsToAdd = representedInBrands.stream()
                            .filter(brand -> !currentBrands.contains(brand))
                            .toList();

                    List<UUID> brandsToRemove = currentBrands.stream()
                            .filter(brand -> !representedInBrands.contains(brand))
                            .toList();

                    Uni<Void> removeUni = Uni.createFrom().voidItem();
                    if (!brandsToRemove.isEmpty()) {
                        String deleteBrandsSql = "DELETE FROM kneobroadcaster__listener_brands WHERE listener_id = $1 AND brand_id = ANY($2)";
                        UUID[] brandsToRemoveArray = brandsToRemove.toArray(new UUID[0]);
                        removeUni = tx.preparedQuery(deleteBrandsSql)
                                .execute(Tuple.of(listenerId, brandsToRemoveArray))
                                .onItem().ignore().andContinueWithNull();
                    }

                    Uni<Void> addUni = Uni.createFrom().voidItem();
                    if (!brandsToAdd.isEmpty()) {
                        String insertBrandsSql = "INSERT INTO kneobroadcaster__listener_brands (listener_id, brand_id, reg_date, rank) VALUES ($1, $2, $3, $4)";
                        List<Tuple> insertParams = brandsToAdd.stream()
                                .map(brandId -> Tuple.of(listenerId, brandId, nowTime, 99))
                                .collect(Collectors.toList());

                        addUni = tx.preparedQuery(insertBrandsSql)
                                .executeBatch(insertParams)
                                .onItem().ignore().andContinueWithNull();
                    }

                    return Uni.combine().all().unis(removeUni, addUni).discardItems();
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
        doc.setTelegramName(row.getString("telegram_name"));
        doc.setCountry(CountryCode.valueOf(row.getString("country")));
        doc.setSlugName(row.getString("slug_name"));
        String lt = row.getString("listener_type");
        if (lt != null) {
            doc.setListenerType(ListenerType.valueOf(lt));
        }

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

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }

    public Uni<Listener> findByTelegramName(String telegramName) {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t WHERE t.telegram_name = $1 LIMIT 1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(telegramName))
                .onItem().transformToUni(rows -> {
                    var it = rows.iterator();
                    if (it.hasNext()) {
                        return from(it.next());
                    } else {
                        return Uni.createFrom().nullItem();
                    }
                });
    }

    public Uni<Listener> findByUserId(Long userId) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE user_id = $1 AND archived = 0";
        return client.preparedQuery(sql)
                .execute(Tuple.of(userId))
                .onItem().transformToUni(rows -> {
                    if (rows.iterator().hasNext()) {
                        return from(rows.iterator().next());
                    } else {
                        return Uni.createFrom().nullItem();
                    }
                });
    }

    private String buildFilterConditions(ListenerFilter filter) {
        return buildFilterConditions(filter, "t");
    }

    private String buildFilterConditions(ListenerFilter filter, String tableAlias) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getCountries() != null && !filter.getCountries().isEmpty()) {
            conditions.append(" AND ").append(tableAlias).append(".country IN (");
            for (int i = 0; i < filter.getCountries().size(); i++) {
                if (i > 0) {
                    conditions.append(", ");
                }
                conditions.append("'").append(filter.getCountries().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        return conditions.toString();
    }
}