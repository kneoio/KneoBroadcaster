package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RadioStationRepository extends AsyncRepository {
    private static final String TABLE_NAME = "kneobroadcaster__brands";

    @Inject
    public RadioStationRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    public Uni<List<RadioStation>> getAll(int limit, int offset) {
        String sql = "SELECT * FROM " + TABLE_NAME + (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<RadioStation> findById(UUID id) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(id);
                });
    }

    public Uni<RadioStation> insert(RadioStation station) {
        String sql = "INSERT INTO " + TABLE_NAME +
                " (brand, playlist, created, listeners_count) " +
                "VALUES ($1, $2, $3, $4) RETURNING id";
        Tuple params = Tuple.of(
                station.getBrand(),
                mapper.valueToTree(station.getPlaylist()),
                station.getCreated(),
                station.getListenersCount()
        );
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(this::findById);
    }

    public Uni<RadioStation> update(UUID id, RadioStation station) {
        String sql = "UPDATE " + TABLE_NAME +
                " SET brand=$1, playlist=$2, created=$3, listeners_count=$4 " +
                "WHERE id=$5";
        Tuple params = Tuple.of(
                station.getBrand(),
                mapper.valueToTree(station.getPlaylist()),
                station.getCreated(),
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
        String sql = "DELETE FROM " + TABLE_NAME + " WHERE id=$1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::rowCount);
    }

    private RadioStation from(Row row) {
        RadioStation station = new RadioStation();
        station.setId(row.getUUID("id"));
        station.setBrand(row.getString("slug_name"));
        station.setCreated(row.getLocalDateTime("reg_date"));
        return station;
    }
}
