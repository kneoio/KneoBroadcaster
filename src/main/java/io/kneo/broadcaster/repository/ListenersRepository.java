package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandListener;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.model.ListenerFilter;
import io.kneo.broadcaster.model.UserData;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    public Uni<List<BrandListener>> findForBrand(String slugName, final int limit, final int offset, IUser user, boolean includeArchived, ListenerFilter filter) {
        String sql = "SELECT l.*, lb.listener_type " +
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
                        String listenerTypeStr = row.getString("listener_type");
                        if (listenerTypeStr != null) {
                            brandListener.setListenerType(ListenerType.valueOf(listenerTypeStr));
                        }
                        return brandListener;
                    });
                })
                .concatenate()
                .collect().asList();
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

    public Uni<Listener> insert(Listener listener, List<UUID> representedInBrands, IUser user) {
        return insert(listener, representedInBrands, ListenerType.REGULAR, user);
    }

    public Uni<Listener> insert(Listener listener, List<UUID> representedInBrands, ListenerType listenerType, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

        String sql = "INSERT INTO " + entityData.getTableName() +
                " (user_id, author, reg_date, last_mod_user, last_mod_date, loc_name, nickname, slug_name, user_data, archived) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id";

        JsonObject localizedNameJson = JsonObject.mapFrom(listener.getLocalizedName());
        JsonObject localizedNickNameJson = toNickNameJson(listener.getNickName());
        JsonObject userDataJson = toUserDataJson(listener.getUserData());

        Tuple params = Tuple.tuple()
                .addLong(listener.getUserId())
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addLong(user.getId())
                .addLocalDateTime(nowTime)
                .addJsonObject(localizedNameJson)
                .addJsonObject(localizedNickNameJson)
                .addString(listener.getSlugName())
                .addJsonObject(userDataJson)
                .addInteger(0);

        return client.withTransaction(tx ->
                tx.preparedQuery(sql)
                        .execute(params)
                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                        .onItem().transformToUni(id ->
                                insertRLSPermissions(tx, id, entityData, user)
                                        .onItem().transformToUni(ignored -> insertBrandAssociations(tx, id, representedInBrands, listenerType, nowTime))
                                        .onItem().transform(ignored -> id)
                        )
        ).onItem().transformToUni(id -> findById(id, user, true));
    }

    private Uni<Void> insertBrandAssociations(io.vertx.mutiny.sqlclient.SqlClient tx, UUID listenerId, List<UUID> representedInBrands, ListenerType listenerType, LocalDateTime nowTime) {
        if (representedInBrands == null || representedInBrands.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String insertBrandsSql = "INSERT INTO kneobroadcaster__listener_brands (listener_id, brand_id, reg_date, rank, listener_type) VALUES ($1, $2, $3, $4, $5)";
        List<Tuple> insertParams = representedInBrands.stream()
                .map(brandId -> Tuple.of(listenerId, brandId, nowTime, 99, listenerType.name()))
                .collect(Collectors.toList());

        return tx.preparedQuery(insertBrandsSql)
                .executeBatch(insertParams)
                .onItem().ignore().andContinueWithNull();
    }

    public Uni<Listener> update(UUID id, Listener listener, List<UUID> representedInBrands, IUser user) {
        return update(id, listener, representedInBrands, null, user);
    }

    public Uni<Listener> update(UUID id, Listener listener, List<UUID> representedInBrands, ListenerType listenerType, IUser user) {
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
                            JsonObject localizedNickNameJson = toNickNameJson(listener.getNickName());
                            JsonObject userDataJson = toUserDataJson(listener.getUserData());

                            return client.withTransaction(tx -> {
                                String sql = "UPDATE " + entityData.getTableName() +
                                        " SET loc_name=$1, nickname=$2, slug_name=$3, user_data=$4, last_mod_user=$5, last_mod_date=$6, archived=$7 " +
                                        "WHERE id=$8";

                                Tuple params = Tuple.tuple()
                                        .addJsonObject(localizedNameJson)
                                        .addJsonObject(localizedNickNameJson)
                                        .addString(listener.getSlugName())
                                        .addJsonObject(userDataJson)
                                        .addLong(user.getId())
                                        .addLocalDateTime(nowTime)
                                        .addInteger(listener.getArchived())
                                        .addUUID(id);

                                return tx.preparedQuery(sql)
                                        .execute(params)
                                        .onFailure().invoke(throwable -> LOGGER.error("Failed to update listener: {} by user: {}", id, user.getId(), throwable))
                                        .onItem().transformToUni(rowSet -> {
                                            if (rowSet.rowCount() == 0) {
                                                return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                            }
                                            return updateBrandAssociations(tx, id, representedInBrands, listenerType, nowTime);
                                        });
                            }).onItem().transformToUni(ignored -> findById(id, user, true));
                        });
            } catch (Exception e) {
                LOGGER.error("Failed to prepare update parameters for listener: {} by user: {}", id, user.getId(), e);
                return Uni.createFrom().failure(e);
            }
        });
    }

    private Uni<Void> updateBrandAssociations(io.vertx.mutiny.sqlclient.SqlClient tx, UUID listenerId, List<UUID> representedInBrands, ListenerType listenerType, LocalDateTime nowTime) {
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
                        String insertBrandsSql = "INSERT INTO kneobroadcaster__listener_brands (listener_id, brand_id, reg_date, rank, listener_type) VALUES ($1, $2, $3, $4, $5)";
                        ListenerType typeToUse = listenerType != null ? listenerType : ListenerType.REGULAR;
                        List<Tuple> insertParams = brandsToAdd.stream()
                                .map(brandId -> Tuple.of(listenerId, brandId, nowTime, 99, typeToUse.name()))
                                .collect(Collectors.toList());

                        addUni = tx.preparedQuery(insertBrandsSql)
                                .executeBatch(insertParams)
                                .onItem().ignore().andContinueWithNull();
                    }

                    Uni<Void> updateTypeUni = Uni.createFrom().voidItem();
                    if (listenerType != null && !representedInBrands.isEmpty()) {
                        String updateTypeSql = "UPDATE kneobroadcaster__listener_brands SET listener_type = $1 WHERE listener_id = $2 AND brand_id = ANY($3)";
                        UUID[] brandIds = representedInBrands.toArray(new UUID[0]);
                        updateTypeUni = tx.preparedQuery(updateTypeSql)
                                .execute(Tuple.of(listenerType.name(), listenerId, brandIds))
                                .onItem().ignore().andContinueWithNull();
                    }

                    return Uni.combine().all().unis(removeUni, addUni, updateTypeUni).discardItems();
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
        doc.setSlugName(row.getString("slug_name"));

        JsonObject localizedNameJson = row.getJsonObject(COLUMN_LOCALIZED_NAME);
        if (localizedNameJson != null) {
            EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
            localizedNameJson.getMap().forEach((key, value) -> 
                localizedName.put(LanguageCode.valueOf(key), (String) value));
            doc.setLocalizedName(localizedName);
        }

        JsonObject nickName = row.getJsonObject("nickname");
        doc.setNickName(fromNickNameJson(nickName));

        JsonObject userDataJson = row.getJsonObject("user_data");
        doc.setUserData(fromUserDataJson(userDataJson));

        doc.setArchived(row.getInteger("archived"));
        return Uni.createFrom().item(doc);
    }

    private JsonObject toNickNameJson(EnumMap<LanguageCode, Set<String>> nick) {
        JsonObject json = new JsonObject();
        if (nick == null || nick.isEmpty()) {
            return json;
        }
        nick.forEach((lang, set) -> {
            if (lang == null || set == null || set.isEmpty()) {
                return;
            }
            JsonArray arr = new JsonArray();
            for (String s : set) {
                if (s == null) {
                    continue;
                }
                String v = s.trim();
                if (!v.isEmpty()) {
                    arr.add(v);
                }
            }
            if (!arr.isEmpty()) {
                json.put(lang.name(), arr);
            }
        });
        return json;
    }

    private EnumMap<LanguageCode, Set<String>> fromNickNameJson(JsonObject json) {
        EnumMap<LanguageCode, Set<String>> out = new EnumMap<>(LanguageCode.class);
        if (json == null || json.isEmpty()) {
            return out;
        }

        json.getMap().forEach((k, v) -> {
            if (k == null || v == null) {
                return;
            }

            LanguageCode lang = LanguageCode.valueOf(k);
            LinkedHashSet<String> set = new LinkedHashSet<>();

            if (v instanceof JsonArray ja) {
                for (int i = 0; i < ja.size(); i++) {
                    String s = Objects.toString(ja.getValue(i), "").trim();
                    if (!s.isEmpty()) {
                        set.add(s);
                    }
                }
            } else if (v instanceof Iterable<?> it) {
                for (Object item : it) {
                    String s = Objects.toString(item, "").trim();
                    if (!s.isEmpty()) {
                        set.add(s);
                    }
                }
            }

            if (!set.isEmpty()) {
                out.put(lang, set);
            }
        });

        return out;
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }

    public Uni<Listener> findByUserDataField(String fieldName, String fieldValue) {
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t WHERE t.user_data->$1 = $2 LIMIT 1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(fieldName, fieldValue))
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

    private JsonObject toUserDataJson(UserData userData) {
        JsonObject json = new JsonObject();
        if (userData == null || userData.getData() == null || userData.getData().isEmpty()) {
            return json;
        }
        userData.getData().forEach(json::put);
        return json;
    }

    private UserData fromUserDataJson(JsonObject json) {
        UserData userData = new UserData();
        if (json == null || json.isEmpty()) {
            return userData;
        }
        json.getMap().forEach((key, value) -> {
            if (key != null && value != null) {
                userData.put(key, value.toString());
            }
        });
        return userData;
    }
}
