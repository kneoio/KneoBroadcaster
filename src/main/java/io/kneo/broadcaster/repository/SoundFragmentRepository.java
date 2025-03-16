package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileData;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.broadcaster.service.DigitalOceanSpacesService;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.FileUpload;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SOUND_FRAGMENT;


@ApplicationScoped
public class SoundFragmentRepository extends AsyncRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SOUND_FRAGMENT);

    private final DigitalOceanSpacesService digitalOceanSpacesService;

    @Inject
    public SoundFragmentRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository, DigitalOceanSpacesService digitalOceanSpacesService) {
        super(client, mapper, rlsRepository);
        this.digitalOceanSpacesService = digitalOceanSpacesService;
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();
        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user) {
        return getAllCount(user.getId(), entityData.getTableName(), entityData.getRlsName());
    }

    public Uni<SoundFragment> findById(UUID uuid, Long userID) {
        return client.preparedQuery(String.format(
                        "SELECT theTable.*, rls.*, files.file_data " +
                                "FROM %s theTable " +
                                "JOIN %s rls ON theTable.id = rls.entity_id " +
                                "JOIN kneobroadcaster__sound_fragment_files files ON theTable.id = files.entity_id " +
                                "WHERE rls.reader = $1 AND theTable.id = $2",
                        entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, false)
                                .onItem().transform(fragment -> {
                                    return fragment;
                                });
                    } else {
                        LOGGER.warn(String.format("No %s found with id: " + uuid, entityData.getTableName()));
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }

    public Uni<List<BrandSoundFragment>> findForBrand(UUID brandId, final int limit, final int offset) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.last_time_played_by_brand " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1 ORDER BY played_by_brand_count";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Uni<SoundFragment> soundFragmentUni = from(row, true);
                    return soundFragmentUni.onItem().transform(soundFragment -> {
                        BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                        brandSoundFragment.setId(row.getUUID("id"));
                        brandSoundFragment.setPlayedByBrandCount(row.getInteger("played_by_brand_count"));
                        brandSoundFragment.setLastTimePlayedByBrand(row.getLocalDateTime("last_time_played_by_brand"));
                        brandSoundFragment.setSoundFragment(soundFragment);
                        return brandSoundFragment;
                    });
                })
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getCountForBrand(UUID brandId) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<FileData> getFileById(UUID fileId, Long userId) {
        String sql = "SELECT file_data, mime_type FROM kneobroadcaster__sound_fragment_files " +
                "WHERE entity_id = $1 AND EXISTS (" +
                "SELECT 1 FROM kneobroadcaster__readers WHERE entity_id = $1 AND reader = $2)";

        return client.preparedQuery(sql)
                .execute(Tuple.of(fileId, userId))
                .onItem().transformToUni(rows -> {
                    if (rows.rowCount() == 0) {
                        return Uni.createFrom().failure(new FileNotFoundException("File not found."));
                    }
                    Row row = rows.iterator().next();
                    return Uni.createFrom().item(new FileData(
                            row.getBuffer("file_data").getBytes(),
                            row.getString("mime_type")
                    ));
                })
                .onFailure().recoverWithUni(e -> Uni.createFrom().failure(e));
    }

    public Uni<Integer> updatePlayedByBrand(UUID brandId, UUID soundFragmentId) {
        String sql = "UPDATE kneobroadcaster__brand_sound_fragments " +
                "SET played_by_brand_count = played_by_brand_count + 1, " +
                "last_time_played_by_brand = NOW() " +
                "WHERE brand_id = $1 AND sound_fragment_id = $2";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, soundFragmentId))
                .onItem().transform(RowSet::rowCount)
                .onFailure().recoverWithUni(e -> {
                    LOGGER.error("Failed to update played_by_brand_count and last_time_played_by_brand", e);
                    return Uni.createFrom().failure(e);
                });
    }

    public Uni<SoundFragment> insert(SoundFragment doc, List<FileUpload> files, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        String sql = String.format(
                "INSERT INTO %s (reg_date, author, last_mod_date, last_mod_user, source, status, type, " +
                        "title, artist, genre, album, played) " +
                        "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $12, $13) RETURNING id;",
                entityData.getTableName()
        );
        String filesSql = "INSERT INTO kneobroadcaster__sound_fragment_files " +
                "(entity_id, file_data, mime_type, size, original_name, version) VALUES ($1, $2, $3, $4, $5, $6)";

        Tuple params = Tuple.of(nowTime, user.getId(), nowTime, user.getId())
                .addString(doc.getSource().name())
                .addInteger(doc.getStatus())
                .addString(doc.getType().name())
                .addString(doc.getTitle())
                .addString(doc.getArtist())
                .addString(doc.getGenre())
                .addString(doc.getAlbum());

        String readersSql = String.format(
                "INSERT INTO %s (reader, entity_id, can_edit, can_delete) VALUES ($1, $2, $3, $4)",
                entityData.getRlsName()
        );

        return client.withTransaction(tx -> {
            return tx.preparedQuery(sql)
                    .execute(params)
                    .onItem().transform(result -> result.iterator().next().getUUID("id"))
                    .onItem().transformToUni(id -> {
                        return tx.preparedQuery(readersSql)
                                .execute(Tuple.of(user.getId(), id, true, true))
                                .onItem().ignore().andContinueWithNull()
                                .onItem().transformToUni(unused -> {
                                    if (files.isEmpty()) {
                                        return Uni.createFrom().item(id);
                                    }

                                    List<Uni<RowSet<Row>>> fileInserts = new ArrayList<>();
                                    for (FileUpload file : files) {
                                        try {
                                            byte[] fileContent = Files.readAllBytes(Paths.get(file.uploadedFileName()));
                                            Uni<RowSet<Row>> fileInsert = tx.preparedQuery(filesSql)
                                                    .execute(Tuple.of(
                                                            id,
                                                            fileContent,
                                                            file.contentType(),
                                                            (int) Files.size(Paths.get(file.uploadedFileName())),
                                                            file.fileName(),
                                                            1
                                                    ));
                                            fileInserts.add(fileInsert);
                                        } catch (IOException e) {
                                            return Uni.createFrom().failure(e);
                                        }
                                    }

                                    return Uni.combine().all().unis(fileInserts)
                                            .discardItems()
                                            .onItem().transform(v -> id);
                                });
                    });
        }).onItem().transformToUni(id -> findById(id, user.getId()));
    }

    public Uni<SoundFragment> update(UUID id, SoundFragment doc, List<FileUpload> files, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (permissions[0]) {
                        String deleteSql = String.format("DELETE FROM %s WHERE entity_id=$1", entityData.getFilesTableName());
                        String filesSql = String.format("INSERT INTO %s (entity_id, file_data, type) VALUES ($1, $2, $3)", entityData.getFilesTableName());

                        return client.withTransaction(tx -> {
                            return tx.preparedQuery(deleteSql)
                                    .execute(Tuple.of(id))
                                    .onItem().transformToUni(ignored -> {
                                        // Insert new files if any
                                        if (!files.isEmpty()) {
                                            List<Uni<RowSet<Row>>> fileInserts = new ArrayList<>();
                                            for (FileUpload file : files) {
                                                try {
                                                    byte[] fileContent = Files.readAllBytes(Paths.get(file.uploadedFileName()));
                                                    Uni<RowSet<Row>> fileInsert = tx.preparedQuery(filesSql)
                                                            .execute(Tuple.of(
                                                                    id,
                                                                    fileContent,
                                                                    file.contentType()
                                                            ));
                                                    fileInserts.add(fileInsert);
                                                } catch (IOException e) {
                                                    return Uni.createFrom().failure(e);
                                                }
                                            }
                                            return Uni.combine().all().unis(fileInserts)
                                                    .discardItems()
                                                    .onItem().transform(v -> id);
                                        }
                                        return Uni.createFrom().item(id);
                                    })
                                    .onItem().transformToUni(unused -> {
                                        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
                                        String updateSql = String.format("UPDATE %s SET last_mod_user=$1, last_mod_date=$2, " +
                                                "source=$3, status=$4, type=$5, title=$6, " +
                                                "artist=$7, genre=$8, album=$9 WHERE id=$10;", entityData.getTableName());

                                        Tuple params = Tuple.of(user.getId(), nowTime)
                                                .addString(doc.getSource().name())
                                                .addInteger(doc.getStatus())
                                                .addString(doc.getType().name())
                                                .addString(doc.getTitle())
                                                .addString(doc.getArtist())
                                                .addString(doc.getGenre())
                                                .addString(doc.getAlbum())
                                                .addUUID(id);

                                        return tx.preparedQuery(updateSql)
                                                .execute(params)
                                                .onItem().transformToUni(rowSet -> {
                                                    if (rowSet.rowCount() == 0) {
                                                        return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                                    }
                                                    return findById(id, user.getId());
                                                });
                                    });
                        });
                    } else {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }
                });
    }

    public Uni<Integer> delete(UUID uuid, IUser user) {
        return delete(uuid, entityData, user);
    }

    private Uni<SoundFragment> from(Row row, boolean setFile) {
        SoundFragment doc = new SoundFragment();
        setDefaultFields(doc, row);
        doc.setSource(SourceType.valueOf(row.getString("source")));
        doc.setStatus(row.getInteger("status"));
        doc.setType(PlaylistItemType.valueOf(row.getString("type")));
        doc.setTitle(row.getString("title"));
        doc.setArtist(row.getString("artist"));
        doc.setGenre(row.getString("genre"));
        doc.setAlbum(row.getString("album"));
        doc.setArchived(row.getInteger("archived"));
        if (setFile) {
            return digitalOceanSpacesService.getFile(row.getString("do_key"))
                    .onItem().transformToUni(file -> {
                        doc.setFilePath(file);
                        return Uni.createFrom().item(doc);
                    });
        }
        return Uni.createFrom().item(doc);
    }

}