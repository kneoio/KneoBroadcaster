package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
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

    public Uni<Listener> findById(UUID id) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return from(iterator.next());
                    }
                    throw new DocumentHasNotFoundException(id);
                });
    }

    public Uni<Listener> insert(Listener listener) {
        LocalDateTime now = LocalDateTime.now();
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (user_id, author, reg_date, last_mod_user, last_mod_date, country, loc_name, nick_name, slug_name, archived) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id";
        Tuple params = Tuple.tuple()
                .addLong(0L)                                  // user_id (placeholder)
                .addLong(0L)                                  // author (placeholder)
                .addLocalDateTime(now)
                .addLong(0L)                                  // last_mod_user (placeholder)
                .addLocalDateTime(now)
                .addString("UNK")                             // country default
                //.add(mapper.valueToTree(Collections.emptyMap())) // loc_name as empty JSON
                //.add(mapper.valueToTree(listener.getNickName()))
                .addString(listener.getSlugName())
                .addInteger(0);                               // archived
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(this::findById);
    }

    public Uni<Listener> update(UUID id, Listener listener) {
        LocalDateTime now = LocalDateTime.now();
        String sql = "UPDATE " + entityData.getTableName() +
                " SET nick_name=$1, slug_name=$2, last_mod_user=$3, last_mod_date=$4 " +
                "WHERE id=$5";
        Tuple params = Tuple.tuple()
              //  .add(mapper.valueToTree(listener.getNickName()))
                .addString(listener.getSlugName())
                .addLong(0L) // last_mod_user (placeholder)
                .addLocalDateTime(now)
                .addUUID(id);
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToUni(rowSet -> {
                    if (rowSet.rowCount() == 0)
                        throw new DocumentHasNotFoundException(id);
                    return findById(id);
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
        doc.setCountry(row.getString("country"));
      /*  listener.setLocName(mapper.treeToValue(row.getJson("loc_name"), Map.class));
        try {
            List<String> nickNames = mapper.treeToValue(row.getJson("nick_name"), List.class);
            listener.setNickName(nickNames);
        } catch (Exception e) {
            listener.setNickName(Collections.emptyList());
        }*/
        doc.setSlugName(row.getString("slug_name"));
        doc.setArchived(row.getInteger("archived"));
        return doc;
    }
}
