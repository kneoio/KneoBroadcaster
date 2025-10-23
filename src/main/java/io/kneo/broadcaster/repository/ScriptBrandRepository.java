package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SCRIPT;

@ApplicationScoped
@Typed(ScriptBrandRepository.class)
public class ScriptBrandRepository extends ScriptRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SCRIPT);

    @Inject
    public ScriptBrandRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
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
                    Script script = fromRow(row);
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
                .onItem().transform(this::fromRow)
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

    private Script fromRow(Row row) {
        Script doc = new Script();
        setDefaultFields(doc, row);
        doc.setName(row.getString("name"));
        doc.setDescription(row.getString("description"));
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
}
