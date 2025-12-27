package io.kneo.broadcaster.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.cnst.FileStorageType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragmentFilter;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlResult;
import io.vertx.mutiny.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SOUND_FRAGMENT;

public abstract class SoundFragmentRepositoryAbstract extends AsyncRepository {
    protected static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SOUND_FRAGMENT);
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentRepositoryAbstract.class);

    public SoundFragmentRepositoryAbstract() {
        super();
    }

    public SoundFragmentRepositoryAbstract(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    protected Uni<SoundFragment> from(Row row, boolean includeGenres, boolean includeFiles, boolean includeLabels) {
        SoundFragment doc = new SoundFragment();
        setDefaultFields(doc, row);
        doc.setSource(SourceType.valueOf(row.getString("source")));
        doc.setStatus(row.getInteger("status"));
        doc.setType(PlaylistItemType.valueOf(row.getString("type")));
        doc.setTitle(row.getString("title"));
        doc.setArtist(row.getString("artist"));
        doc.setAlbum(row.getString("album"));
        
        if (row.getValue("length") != null) {
            Long lengthMillis = row.getLong("length");
            doc.setLength(Duration.ofMillis(lengthMillis));
        }
        doc.setArchived(row.getInteger("archived"));
        doc.setSlugName(row.getString("slug_name"));
        doc.setDescription(row.getString("description"));

        Uni<SoundFragment> uni = Uni.createFrom().item(doc);

        if (includeGenres) {
            uni = uni.chain(d -> loadGenres(d.getId()).onItem().transform(genres -> {
                d.setGenres(genres);
                return d;
            }));
        } else {
            doc.setGenres(List.of());
        }

        if (includeLabels) {
            uni = uni.chain(d -> loadLabels(d.getId()).onItem().transform(labels -> {
                d.setLabels(labels);
                return d;
            }));
        } else {
            doc.setLabels(List.of());
        }

        if (includeFiles) {
            String fileQuery = "SELECT id, reg_date, last_mod_date, parent_table, parent_id, archived, archived_date, storage_type, mime_type, slug_name, file_original_name, file_key FROM _files WHERE parent_table = '" + entityData.getTableName() + "' AND parent_id = $1 AND archived = 0 ORDER BY reg_date ASC";
            uni = uni.chain(d -> client.preparedQuery(fileQuery)
                    .execute(Tuple.of(d.getId()))
                    .onItem().transform(rowSet -> {
                        List<FileMetadata> files = new ArrayList<>();
                        for (Row fileRow : rowSet) {
                            FileMetadata f = new FileMetadata();
                            f.setId(fileRow.getLong("id"));
                            f.setRegDate(fileRow.getLocalDateTime("reg_date").atZone(ZoneId.systemDefault()));
                            f.setLastModifiedDate(fileRow.getLocalDateTime("last_mod_date").atZone(ZoneId.systemDefault()));
                            f.setParentTable(fileRow.getString("parent_table"));
                            f.setParentId(fileRow.getUUID("parent_id"));
                            f.setArchived(fileRow.getInteger("archived"));
                            if (fileRow.getLocalDateTime("archived_date") != null)
                                f.setArchivedDate(fileRow.getLocalDateTime("archived_date"));
                            f.setFileStorageType(FileStorageType.valueOf(fileRow.getString("storage_type")));
                            f.setMimeType(fileRow.getString("mime_type"));
                            f.setSlugName(fileRow.getString("slug_name"));
                            f.setFileOriginalName(fileRow.getString("file_original_name"));
                            f.setFileKey(fileRow.getString("file_key"));
                            files.add(f);
                        }
                        d.setFileMetadataList(files);
                        if (files.isEmpty()) markAsCorrupted(d.getId()).subscribe().with(r -> {}, e -> {});
                        return d;
                    }));
        } else {
            doc.setFileMetadataList(List.of());
        }

        return uni;
    }

    private Uni<List<UUID>> loadLabels(UUID soundFragmentId) {
        String sql = "SELECT label_id FROM kneobroadcaster__sound_fragment_labels WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("label_id"))
                .collect().asList();
    }


    private Uni<List<UUID>> loadGenres(UUID soundFragmentId) {
        String sql = "SELECT g.id FROM kneobroadcaster__genres g " +
                "JOIN kneobroadcaster__sound_fragment_genres sfg ON g.id = sfg.genre_id " +
                "WHERE sfg.sound_fragment_id = $1 ORDER BY g.identifier";

        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("id"))
                .collect().asList();
    }

    public Uni<Integer> markAsCorrupted(UUID uuid) {
        IUser user = SuperUser.build();
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), uuid)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException(
                                "User does not have edit permission", user.getUserName(), uuid));
                    }

                    String sql = String.format("UPDATE %s SET archived = -1, last_mod_date = $1, last_mod_user = $2 WHERE id = $3",
                            entityData.getTableName());
                    return client.preparedQuery(sql)
                            .execute(Tuple.of(ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime(), user.getId(), uuid))
                            .onItem().transform(SqlResult::rowCount);
                });
    }

    protected String buildFilterConditions(SoundFragmentFilter filter) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getGenre() != null && !filter.getGenre().isEmpty()) {
            conditions.append(" AND EXISTS (SELECT 1 FROM kneobroadcaster__sound_fragment_genres sfg2 ")
                    .append("WHERE sfg2.sound_fragment_id = t.id AND sfg2.genre_id IN (");
            for (int i = 0; i < filter.getGenre().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getGenre().get(i).toString()).append("'");
            }
            conditions.append("))");
        }

        if (filter.getLabels() != null && !filter.getLabels().isEmpty()) {
            conditions.append(" AND EXISTS (SELECT 1 FROM kneobroadcaster__sound_fragment_labels sfl ")
                    .append("WHERE sfl.id = t.id AND sfl.label_id IN (");
            for (int i = 0; i < filter.getLabels().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getLabels().get(i).toString()).append("'");
            }
            conditions.append("))");
        }

        if (filter.getSource() != null && !filter.getSource().isEmpty()) {
            conditions.append(" AND t.source IN (");
            for (int i = 0; i < filter.getSource().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getSource().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        if (filter.getType() != null && !filter.getType().isEmpty()) {
            conditions.append(" AND t.type IN (");
            for (int i = 0; i < filter.getType().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getType().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        return conditions.toString();
    }

    private Duration parsePostgresIntervalToDuration(String intervalStr) {
        if (intervalStr == null || intervalStr.trim().isEmpty()) {
            return null;
        }
        
        try {
            // PostgreSQL INTERVAL typically returns in HH:MM:SS or HH:MM:SS.mmm format
            if (intervalStr.contains(":")) {
                String[] parts = intervalStr.split(":");
                if (parts.length == 3) {
                    long hours = Long.parseLong(parts[0]);
                    long minutes = Long.parseLong(parts[1]);
                    
                    // Handle fractional seconds (e.g., "45.632")
                    String secondsPart = parts[2];
                    if (secondsPart.contains(".")) {
                        String[] secondsAndMillis = secondsPart.split("\\.");
                        long seconds = Long.parseLong(secondsAndMillis[0]);
                        int millis = Integer.parseInt(secondsAndMillis[1]);
                        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds).plusMillis(millis);
                    } else {
                        long seconds = Long.parseLong(secondsPart);
                        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
                    }
                } else if (parts.length == 2) {
                    long minutes = Long.parseLong(parts[0]);
                    String secondsPart = parts[1];
                    if (secondsPart.contains(".")) {
                        String[] secondsAndMillis = secondsPart.split("\\.");
                        long seconds = Long.parseLong(secondsAndMillis[0]);
                        int millis = Integer.parseInt(secondsAndMillis[1]);
                        return Duration.ofMinutes(minutes).plusSeconds(seconds).plusMillis(millis);
                    } else {
                        long seconds = Long.parseLong(secondsPart);
                        return Duration.ofMinutes(minutes).plusSeconds(seconds);
                    }
                }
            }
            
            // Fallback: try to parse as seconds if it's a numeric value
            long seconds = Long.parseLong(intervalStr);
            return Duration.ofSeconds(seconds);
            
        } catch (Exception e) {
            // Log error but return null to avoid breaking the whole entity loading
            LOGGER.warn("Failed to parse PostgreSQL interval: {}", intervalStr, e);
            return null;
        }
    }
}
