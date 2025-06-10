package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.Profile;
import io.kneo.broadcaster.model.cnst.AnnouncementFrequency;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.PROFILE;

@ApplicationScoped
public class ProfileRepository extends AsyncRepository {
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(PROFILE);

    @Inject
    public ProfileRepository(PgPool client, ObjectMapper mapper) {
        super(client, mapper, null);
    }

    public Uni<List<Profile>> getAll(int limit, int offset, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() +
                (limit > 0 ? " LIMIT " + limit + " OFFSET " + offset : "");
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Profile> findById(UUID id) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(id);
                });
    }

    public Uni<Profile> findByName(String name) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE name = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(name))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) return from(iterator.next());
                    throw new DocumentHasNotFoundException(name);
                });
    }

    public Uni<Profile> insert(Profile profile) {
        String sql = "INSERT INTO " + entityData.getTableName() +
                " (name, description, allowed_genres, announcement_frequency, explicit_content, language) " +
                "VALUES ($1, $2, $3, $4, $5, $6) RETURNING id";

        Tuple params = Tuple.tuple()
                .addString(profile.getName())
                .addString(profile.getDescription())
                .addValue(mapper.valueToTree(profile.getAllowedGenres()))
                .addString(profile.getAnnouncementFrequency().name().toLowerCase())
                .addBoolean(profile.isExplicitContent())
                .addString(profile.getLanguage() != null ? profile.getLanguage().name() : null);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(result -> result.iterator().next().getUUID("id"))
                .onItem().transformToUni(this::findById);
    }

    public Uni<Profile> update(UUID id, Profile profile) {
        String sql = "UPDATE " + entityData.getTableName() +
                " SET name=$1, description=$2, allowed_genres=$3, " +
                "announcement_frequency=$4, explicit_content=$5, language=$6 " +
                "WHERE id=$7";

        Tuple params = Tuple.tuple()
                .addString(profile.getName())
                .addString(profile.getDescription())
                .addValue(mapper.valueToTree(profile.getAllowedGenres()))
                .addString(profile.getAnnouncementFrequency().name().toLowerCase())
                .addBoolean(profile.isExplicitContent())
                .addString(profile.getLanguage() != null ? profile.getLanguage().name() : null)
                .addUUID(id);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToUni(rowSet -> {
                    if (rowSet.rowCount() == 0) throw new DocumentHasNotFoundException(id);
                    return findById(id);
                });
    }

    public Uni<Integer> delete(UUID id) {
        String sql = "DELETE FROM " + entityData.getTableName() + " WHERE id=$1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<Integer> getAllCount(IUser user) {
        return getAllCount(user.getId(), entityData.getTableName(), entityData.getRlsName());
    }

    private Profile from(Row row) {
        Profile profile = new Profile();
        setDefaultFields(profile, row);

        profile.setName(row.getString("name"));
        profile.setDescription(row.getString("description"));

        JsonArray genresJson = row.getJsonArray("allowed_genres");
        if (genresJson != null) {
            List<String> genres = new ArrayList<>();
            for (int i = 0; i < genresJson.size(); i++) {
                genres.add(genresJson.getString(i));
            }
            //TODO genre now is reference
            //profile.setAllowedGenres(genres);
        }

        String frequency = row.getString("announcement_frequency");
        if (frequency != null) {
            profile.setAnnouncementFrequency(AnnouncementFrequency.valueOf(frequency.toUpperCase()));
        }

        profile.setExplicitContent(row.getBoolean("explicit_content"));

        String lang = row.getString("language");
        if (lang != null) {
            profile.setLanguage(LanguageCode.valueOf(lang));
        }

        profile.setArchived(row.getInteger("archived"));

        return profile;
    }
}