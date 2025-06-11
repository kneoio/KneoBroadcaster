package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.Genre;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.table.EntityData;
import io.kneo.officeframe.model.Organization;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.GENRE;

@ApplicationScoped
public class GenreRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(GENRE);
    private static final String BASE_REQUEST = String.format("SELECT * FROM %s t ", entityData.getTableName());


    @Inject
    public GenreRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }


    public Uni<List<Genre>> getAll(final int limit, final int offset) {
        return client.query(getBaseSelect(BASE_REQUEST, limit, offset))
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from).collect().asList();
    }

    public Uni<Integer> getAllCount() {
        return getAllCount(entityData.getTableName());
    }

    public Uni<Genre> findById(UUID uuid) {
        return findById(uuid, entityData, this::from);
    }

    public Uni<Genre> findByIdentifier(String identifier) {
        return findByIdentifier(identifier, entityData, this::from);
    }

    private Genre from(Row row) {
        Genre doc = new Genre();
        setDefaultFields(doc, row);
        doc.setIdentifier(row.getString(COLUMN_IDENTIFIER));
        setLocalizedNames(doc, row);
        return doc;
    }

    public UUID insert(Organization node, Long user) {

        return node.getId();
    }

    public int delete(Long id) {

        return 1;
    }
}
