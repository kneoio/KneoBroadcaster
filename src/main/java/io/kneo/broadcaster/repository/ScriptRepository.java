package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SCRIPT;

@ApplicationScoped
public class ScriptRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SCRIPT);

    @Inject
    public ScriptRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Script>> getAll(int limit, int offset, boolean includeArchived, final IUser user) {
        String sql = """
            SELECT t.*, rls.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels
            FROM %s t
            JOIN %s rls ON t.id = rls.entity_id
            WHERE rls.reader = %s
        """.formatted(entityData.getTableName(), entityData.getRlsName(), user.getId());

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

    public Uni<List<Script>> getAllShared(int limit, int offset, final IUser user) {
        String sql = """
            SELECT t.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels
            FROM %s t
            WHERE (t.access_level = 1 OR EXISTS (
                SELECT 1 FROM %s rls WHERE rls.entity_id = t.id AND rls.reader = %s
            )) AND t.archived = 0
        """.formatted(entityData.getTableName(), entityData.getRlsName(), user.getId());

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

    public Uni<Integer> getAllSharedCount(IUser user) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t " +
                "WHERE (t.access_level = 1 OR EXISTS (SELECT 1 FROM " + entityData.getRlsName() +
                " rls WHERE rls.entity_id = t.id AND rls.reader = " + user.getId() + ")) AND t.archived = 0";

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Script> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = """
            SELECT theTable.*, rls.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = theTable.id) AS labels
            FROM %s theTable
            JOIN %s rls ON theTable.id = rls.entity_id
            WHERE rls.reader = $1 AND theTable.id = $2
        """.formatted(entityData.getTableName(), entityData.getRlsName());

        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }

        return client.preparedQuery(sql)
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

    public Uni<Script> insert(Script script, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                String sql = "INSERT INTO " + entityData.getTableName() +
                        " (author, reg_date, last_mod_user, last_mod_date, name, description, access_level) " +
                        "VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING id";

                OffsetDateTime now = OffsetDateTime.now();

                Tuple params = Tuple.tuple()
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addLong(user.getId())
                        .addOffsetDateTime(now)
                        .addString(script.getName())
                        .addString(script.getDescription())
                        .addInteger(script.getAccessLevel());

                return client.withTransaction(tx ->
                        tx.preparedQuery(sql)
                                .execute(params)
                                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                                .onItem().transformToUni(id ->
                                        upsertLabels(tx, id, script.getLabels())
                                                .onItem().transformToUni(ignored ->
                                                        insertRLSPermissions(tx, id, entityData, user)
                                                )
                                                .onItem().transform(ignored -> id)
                                )
                ).onItem().transformToUni(id -> findById(id, user, true));
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        });
    }

    public Uni<Script> update(UUID id, Script script, IUser user) {
        return Uni.createFrom().deferred(() -> {
            try {
                return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                        .onItem().transformToUni(permissions -> {
                            if (!permissions[0]) {
                                return Uni.createFrom().failure(
                                        new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id)
                                );
                            }

                            String sql = "UPDATE " + entityData.getTableName() +
                                    " SET name=$1, description=$2, access_level=$3, last_mod_user=$4, last_mod_date=$5 " +
                                    "WHERE id=$6";

                            OffsetDateTime now = OffsetDateTime.now();

                            Tuple params = Tuple.tuple()
                                    .addString(script.getName())
                                    .addString(script.getDescription())
                                    .addInteger(script.getAccessLevel())
                                    .addLong(user.getId())
                                    .addOffsetDateTime(now)
                                    .addUUID(id);

                            return client.withTransaction(tx ->
                                    upsertLabels(tx, id, script.getLabels())
                                            .onItem().transformToUni(ignored ->
                                                    tx.preparedQuery(sql).execute(params)
                                            )
                            ).onItem().transformToUni(rowSet -> {
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

    private Uni<Void> upsertLabels(SqlClient tx, UUID scriptId, List<UUID> labels) {
        String deleteSql = "DELETE FROM mixpla_script_labels WHERE script_id = $1";
        if (labels == null || labels.isEmpty()) {
            return tx.preparedQuery(deleteSql)
                    .execute(Tuple.of(scriptId))
                    .replaceWithVoid();
        }
        String insertSql = "INSERT INTO mixpla_script_labels (script_id, label_id) VALUES ($1, $2) ON CONFLICT DO NOTHING";
        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(scriptId))
                .chain(() -> Multi.createFrom().iterable(labels)
                        .onItem().transformToUni(labelId ->
                                tx.preparedQuery(insertSql).execute(Tuple.of(scriptId, labelId))
                        )
                        .merge()
                        .collect().asList()
                        .replaceWithVoid());
    }

    private Script from(Row row) {
        Script doc = new Script();
        setDefaultFields(doc, row);
        doc.setName(row.getString("name"));
        doc.setDescription(row.getString("description"));
        doc.setAccessLevel(row.getInteger("access_level"));
        doc.setArchived(row.getInteger("archived"));

        Object[] arr = row.getArrayOfUUIDs("labels");
        if (arr != null && arr.length > 0) {
            List<UUID> labels = new ArrayList<>();
            for (Object o : arr) {
                labels.add((UUID) o);
            }
            doc.setLabels(labels);
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
                        return Uni.createFrom().failure(
                                new DocumentModificationAccessException("User does not have delete permission", user.getUserName(), id)
                        );
                    }

                    String checkScenesSql = "SELECT COUNT(*) FROM mixpla_script_scenes WHERE script_id = $1";
                    return client.preparedQuery(checkScenesSql)
                            .execute(Tuple.of(id))
                            .onItem().transform(rows -> rows.iterator().next().getInteger(0))
                            .onItem().transformToUni(sceneCount -> {
                                if (sceneCount != null && sceneCount > 0) {
                                    return Uni.createFrom().failure(new IllegalStateException(
                                            "Cannot delete script: it has " + sceneCount + " scene(s)"
                                    ));
                                }

                                return client.withTransaction(tx -> {
                                    String deleteLabelsSql = "DELETE FROM mixpla_script_labels WHERE script_id = $1";
                                    String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                                    String deleteDocSql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                                    return tx.preparedQuery(deleteLabelsSql)
                                            .execute(Tuple.of(id))
                                            .onItem().transformToUni(ignored ->
                                                    tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id))
                                            )
                                            .onItem().transformToUni(ignored ->
                                                    tx.preparedQuery(deleteDocSql).execute(Tuple.of(id))
                                            )
                                            .onItem().transform(RowSet::rowCount);
                                });
                            });
                });
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }

    public Uni<List<BrandScript>> findForBrand(UUID brandId, final int limit, final int offset,
                                                boolean includeArchived, IUser user) {
        String sql = "SELECT t.*, bs.rank, bs.active, " +
                "ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_scripts bs ON t.id = bs.script_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bs.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        sql += " ORDER BY bs.rank ASC, t.name ASC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    BrandScript brandScript = createBrandScript(row, brandId);
                    Script script = from(row);
                    brandScript.setScript(script);
                    return brandScript;
                })
                .collect().asList();
    }

    public Uni<Integer> findForBrandCount(UUID brandId, boolean includeArchived, IUser user) {
        String sql = "SELECT COUNT(*) " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_scripts bs ON t.id = bs.script_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bs.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<List<Script>> getBrandScripts(UUID brandId, final int limit, final int offset) {
        String sql = "SELECT t.*, " +
                "ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_scripts bs ON t.id = bs.script_id " +
                "WHERE bs.brand_id = $1 AND (t.archived IS NULL OR t.archived = 0) AND bs.active = true " +
                "ORDER BY bs.rank ASC, t.name ASC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    private BrandScript createBrandScript(Row row, UUID brandId) {
        BrandScript brandScript = new BrandScript();
        brandScript.setId(row.getUUID("id"));
        brandScript.setDefaultBrandId(brandId);
        brandScript.setRank(row.getInteger("rank"));
        brandScript.setActive(row.getBoolean("active"));
        return brandScript;
    }

    public Uni<List<BrandScript>> findForBrandByName(String brandName, final int limit, final int offset, IUser user) {
        String sql = "SELECT t.*, " +
                "ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels " +
                "FROM " + entityData.getTableName() + " t " +
                "WHERE (t.access_level = 1 OR EXISTS (" +
                "SELECT 1 FROM " + entityData.getRlsName() + " rls WHERE rls.entity_id = t.id AND rls.reader = " + user.getId() + 
                ")) AND t.archived = 0" +
                " ORDER BY t.access_level ASC, t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> {
                    BrandScript brandScript = new BrandScript();
                    brandScript.setId(row.getUUID("id"));
                    Script script = from(row);
                    brandScript.setScript(script);
                    return brandScript;
                })
                .collect().asList();
    }

    public Uni<Integer> findForBrandByNameCount(String brandName, IUser user) {
        String sql = "SELECT COUNT(*) " +
                "FROM " + entityData.getTableName() + " t " +
                "WHERE (t.access_level = 1 OR EXISTS (" +
                "SELECT 1 FROM " + entityData.getRlsName() + " rls WHERE rls.entity_id = t.id AND rls.reader = " + user.getId() + 
                ")) AND t.archived = 0";

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }
}
