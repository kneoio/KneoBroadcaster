package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileData;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.broadcaster.service.external.DigitalOceanSpacesService;
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
// import java.io.IOException; // Not directly used in the snippet after changes
// import java.nio.file.Files; // Not directly used in the snippet after changes
// import java.nio.file.Paths; // Not directly used in the snippet after changes
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
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
                        "SELECT theTable.*, rls.* " +
                                "FROM %s theTable " +
                                "JOIN %s rls ON theTable.id = rls.entity_id " +
                                "WHERE rls.reader = $1 AND theTable.id = $2",
                        entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, true);
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
                        brandSoundFragment.setPlayedTime(row.getLocalDateTime("last_time_played_by_brand"));
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
        String sql = "UPDATE "  + entityData.getTableName() +
                " SET played_by_brand_count = played_by_brand_count + 1, " +
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
        if (files == null || files.isEmpty() || files.get(0) == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("A file is required to create a SoundFragment."));
        }
        FileUpload fileToUpload = files.get(0);

        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        String doKey = user.getUserName() + "/" + UUID.randomUUID().toString() + "-" + fileToUpload.fileName();

        String sql = String.format(
                "INSERT INTO %s (reg_date, author, last_mod_date, last_mod_user, source, status, type, " +
                        "title, artist, genre, album, slug_name, do_key, archived) " +
                        "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) RETURNING id;",
                entityData.getTableName()
        );

        Tuple params = Tuple.of(nowTime, user.getId(), nowTime, user.getId())
                .addString(doc.getSource().name())
                .addInteger(doc.getStatus())
                .addString(doc.getType().name())
                .addString(doc.getTitle())
                .addString(doc.getArtist())
                .addString(doc.getGenre())
                .addString(doc.getAlbum())
                .addString(doc.getSlugName())
                .addString(doKey)
                .addInteger(0);

        String readersSql = String.format(
                "INSERT INTO %s (reader, entity_id, can_edit, can_delete) VALUES ($1, $2, $3, $4)",
                entityData.getRlsName()
        );

        return digitalOceanSpacesService.uploadFile(doKey, fileToUpload)
                .onItem().transformToUni(v -> client.withTransaction(tx -> tx.preparedQuery(sql)
                        .execute(params)
                        .onItem().transform(result -> result.iterator().next().getUUID("id"))
                        .onItem().transformToUni(id -> tx.preparedQuery(readersSql)
                                .execute(Tuple.of(user.getId(), id, true, true))
                                .onItem().transform(ignored -> id)
                        )
                ))
                .onItem().transformToUni(id -> findById(id, user.getId()));
    }

    public Uni<SoundFragment> update(UUID id, SoundFragment doc, List<FileUpload> files, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }

                    return findById(id, user.getId())
                            .onItem().transformToUni(existingDoc -> {
                                Uni<String> doKeyUni;
                                String newDoKeyForUpdate = existingDoc.getDoKey();

                                if (files != null && !files.isEmpty() && files.get(0) != null) {
                                    FileUpload newFileToUpload = files.get(0);
                                    String generatedNewDoKey = user.getUserName() + "/" + UUID.randomUUID() + "-" + newFileToUpload.fileName();
                                    newDoKeyForUpdate = generatedNewDoKey;

                                    Uni<Void> uploadAndDeleteOldUni = digitalOceanSpacesService.uploadFile(generatedNewDoKey, newFileToUpload);
                                    if (existingDoc.getDoKey() != null && !existingDoc.getDoKey().isBlank()) {
                                        uploadAndDeleteOldUni = uploadAndDeleteOldUni
                                                .flatMap(v -> digitalOceanSpacesService.deleteFile(existingDoc.getDoKey())
                                                        .onFailure().recoverWithItem((Void)null)
                                                );
                                    }
                                    doKeyUni = uploadAndDeleteOldUni.map(v -> generatedNewDoKey);
                                } else {
                                    doKeyUni = Uni.createFrom().item(existingDoc.getDoKey());
                                }

                                final String finalNewDoKeyForUpdate = newDoKeyForUpdate;
                                return doKeyUni.onItem().transformToUni(processedDoKey -> {
                                    LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
                                    String updateSql = String.format("UPDATE %s SET last_mod_user=$1, last_mod_date=$2, " +
                                                    "source=$3, status=$4, type=$5, title=$6, " +
                                                    "artist=$7, genre=$8, album=$9, slug_name=$10, do_key=$11 WHERE id=$12;",
                                            entityData.getTableName());

                                    Tuple params = Tuple.of(user.getId(), nowTime)
                                            .addString(doc.getSource().name())
                                            .addInteger(doc.getStatus())
                                            .addString(doc.getType().name())
                                            .addString(doc.getTitle())
                                            .addString(doc.getArtist())
                                            .addString(doc.getGenre())
                                            .addString(doc.getAlbum())
                                            .addString(doc.getSlugName())
                                            .addString(finalNewDoKeyForUpdate)
                                            .addUUID(id);

                                    return client.withTransaction(tx -> tx.preparedQuery(updateSql)
                                            .execute(params)
                                            .onItem().transformToUni(rowSet -> {
                                                if (rowSet.rowCount() == 0) {
                                                    return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                                }
                                                return findById(id, user.getId());
                                            }));
                                });
                            });
                });
    }

    public Uni<Integer> delete(UUID uuid, IUser user) {
        return findById(uuid, user.getId())
                .onItem().transformToUni(soundFragment -> {
                    Uni<Void> deleteFileUni = Uni.createFrom().voidItem();
                    if (soundFragment.getDoKey() != null && !soundFragment.getDoKey().isBlank()) {
                        deleteFileUni = digitalOceanSpacesService.deleteFile(soundFragment.getDoKey())
                                .onFailure().recoverWithUni(e -> {
                                    LOGGER.error("Failed to delete file {} from DO Spaces for SoundFragment {}. DB record deletion will proceed.", soundFragment.getDoKey(), uuid, e);
                                    return Uni.createFrom().voidItem();
                                });
                    }
                    return deleteFileUni.onItem().transformToUni(v -> super.delete(uuid, entityData, user));
                });
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

        doc.setSlugName(row.getString("slug_name"));
        doc.setDoKey(row.getString("do_key"));

        if (row.getString("description") != null) {
            doc.setDescription(row.getString("description"));
        }

        if (setFile && doc.getDoKey() != null && !doc.getDoKey().isBlank()) {
            return digitalOceanSpacesService.getFile(doc.getDoKey())
                    .onItem().transform(filePath -> {
                        doc.setFilePath(filePath);
                        return doc;
                    })
                    .onFailure().recoverWithUni(e -> {
                        LOGGER.warn("Failed to fetch file from DO for key {}: {}. SoundFragment will not have filePath.", doc.getDoKey(), e.getMessage());
                        return Uni.createFrom().item(doc);
                    });
        }
        return Uni.createFrom().item(doc);
    }
}