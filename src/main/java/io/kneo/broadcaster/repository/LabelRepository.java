package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.Label;
import io.kneo.core.repository.AsyncRepository;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@Deprecated
@ApplicationScoped
public class LabelRepository extends AsyncRepository {
    private static final String LABELS_TABLE = "__labels";
    private static final String BASE_REQUEST = String.format("SELECT * FROM %s t ", LABELS_TABLE);

    @Inject
    public LabelRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    public Uni<List<Label>> getAll(final int limit, final int offset) {
        String sql = BASE_REQUEST + "WHERE t.archived = 0 AND t.category = 'sound_fragment' ORDER BY t.identifier LIMIT $1 OFFSET $2";
        return client.preparedQuery(sql)
                .execute(Tuple.of(limit, offset))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Label> findById(UUID uuid) {
        String sql = BASE_REQUEST + "WHERE t.id = $1 AND t.archived = 0 AND t.category = 'sound_fragment'";
        return client.preparedQuery(sql)
                .execute(Tuple.of(uuid))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().first();
    }

    public Uni<Integer> getAllCount() {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE archived = 0 AND category = 'sound_fragment'", LABELS_TABLE);
        return client.preparedQuery(sql)
                .execute()
                .onItem().transform(row -> row.iterator().next().getInteger("count"));
    }

    private Label from(Row row) {
        Label doc = new Label();
        setDefaultFields(doc, row);
        doc.setIdentifier(row.getString(COLUMN_IDENTIFIER));
        doc.setSlugName(row.getString(COLUMN_IDENTIFIER));
        doc.setColor(row.getString("color"));
        doc.setArchived(row.getInteger("archived"));
        setLocalizedNames(doc, row);
        return doc;
    }
}
