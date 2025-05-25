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
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.file.FileSystem;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;
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
    private final Vertx vertx;

    @Inject
    public SoundFragmentRepository(PgPool client,
                                   ObjectMapper mapper,
                                   RLSRepository rlsRepository,
                                   DigitalOceanSpacesService digitalOceanSpacesService,
                                   Vertx vertx) {
        super(client, mapper, rlsRepository);
        this.digitalOceanSpacesService = digitalOceanSpacesService;
        this.vertx = vertx;
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId() + " ORDER BY t.last_mod_date DESC";
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
        final String sql = "SELECT sf.do_key, sf.mime_type " +
                "FROM " + entityData.getTableName() + " sf " +
                "JOIN " + entityData.getRlsName() + " rls ON sf.id = rls.entity_id " +
                "WHERE sf.id = $1 AND rls.reader = $2";

        return client.preparedQuery(sql)
                .execute(Tuple.of(fileId, userId))
                .onItem().transformToUni(rows -> {
                    if (rows.rowCount() == 0) {
                        LOGGER.warn("SoundFragment lookup: No record found for id {} with read permission for user {}.", fileId, userId);
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(
                                String.format("File not found (ID: %s) or access denied.", fileId)
                        ));
                    }
                    Row row = rows.iterator().next();
                    String doKey = row.getString("do_key");
                    String mimeType = row.getString("mime_type"); // Use mime_type from DB


                    if (doKey == null || doKey.isBlank()) {
                        LOGGER.warn("SoundFragment (id: {}) has a null or empty 'do_key'. Cannot fetch from DigitalOcean Spaces.", fileId);
                        return Uni.createFrom().failure(new FileNotFoundException(
                                String.format("File data is not available for ID: %s (missing storage key).", fileId)
                        ));
                    }

                    return digitalOceanSpacesService.getFile(doKey)
                            .onItem().transformToUni(filePath -> {
                                if (filePath == null) {
                                    LOGGER.error("DigitalOceanSpacesService returned an empty/null file path for do_key: {} (SoundFragment ID: {})", doKey, fileId);
                                    return Uni.createFrom().failure(new IOException(
                                            String.format("Failed to obtain a valid temporary file path for DO key: %s", doKey)
                                    ));
                                }
                                LOGGER.debug("File for do_key '{}' (SoundFragment ID '{}') downloaded to temporary path: {}", doKey, fileId, filePath);

                                FileSystem fs = this.vertx.fileSystem();
                                return fs.readFile(String.valueOf(filePath))
                                        .onItem().transform(buffer -> new FileData(buffer.getBytes(), mimeType))
                                        .eventually(() -> {
                                            LOGGER.debug("Attempting to delete temporary file: {}", filePath);
                                            return fs.delete(String.valueOf(filePath))
                                                    .onFailure().invoke(e -> LOGGER.warn("Failed to delete temporary file '{}': {}", filePath, e.getMessage()));
                                        });
                            })
                            .onFailure().transform(ex -> {
                                LOGGER.error("Error in DigitalOcean/FileSystem pipeline for do_key '{}' (SoundFragment ID '{}'). Error: {}",
                                        doKey, fileId, ex.getMessage(), ex);
                                if (ex instanceof FileNotFoundException) {
                                    return ex;
                                }
                                return new FileNotFoundException(
                                        String.format("Failed to retrieve file content from storage for ID: %s. Cause: %s", fileId, ex.getMessage())
                                );
                            });
                })
                .onFailure(FileNotFoundException.class).recoverWithUni(fnfException -> {
                    return Uni.createFrom().failure(fnfException);
                })
                .onFailure().recoverWithUni(otherException -> {
                    LOGGER.error("Unexpected error while attempting to fetch file metadata or content for SoundFragment ID '{}', User ID '{}'. SQL was: '{}'. Error: {}",
                            fileId, userId, sql, otherException.getMessage(), otherException);
                    return Uni.createFrom().failure(new RuntimeException(
                            String.format("An unexpected error occurred while fetching file data for ID: %s.", fileId), otherException
                    ));
                });
    }

    public Uni<Integer> updatePlayedByBrand(UUID brandId, UUID soundFragmentId) {
        String sql = "UPDATE " + entityData.getTableName() +
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

    public Uni<SoundFragment> insert(SoundFragment doc, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        String determinedDoKey = null;
        Uni<String> mimeTypeUni = Uni.createFrom().item((String) null);
        Uni<Void> uploadUni = Uni.createFrom().voidItem();
        return executeInsertTransaction(doc, user, nowTime, determinedDoKey, mimeTypeUni, uploadUni);
    }

    public Uni<SoundFragment> insert(SoundFragment doc, List<String> files, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();

        String determinedDoKey = null;
        Uni<String> mimeTypeUni = Uni.createFrom().item((String) null);
        Uni<Void> uploadUni = Uni.createFrom().voidItem();

        String pathCandidate = null;
        if (!files.isEmpty()) {
            pathCandidate = files.get(0);
        }

        String mimeType = "";
        if (pathCandidate != null && !pathCandidate.trim().isEmpty()) {
            String actualFileToUploadPath = pathCandidate.trim();
            String actualOriginalFileName = Paths.get(actualFileToUploadPath).getFileName().toString();
            determinedDoKey = user.getUserName() + "/" + UUID.randomUUID() + "-" + actualOriginalFileName;

            final String finalKey = determinedDoKey;
            mimeType = detectMimeType(actualFileToUploadPath);
            mimeTypeUni = wrapToUni(mimeType);
            //TODO it will need compensation logic to clean orphans
            uploadUni = digitalOceanSpacesService.uploadFile(finalKey, actualFileToUploadPath, mimeType);
        }

        return executeInsertTransaction(doc, user, nowTime, determinedDoKey, mimeTypeUni, uploadUni);
    }

    public Uni<SoundFragment> update(UUID id, SoundFragment doc, List<String> files, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }

                    return findById(id, user.getId()) // Fetches existingDoc, populating its mime_type via from()
                            .onItem().transformToUni(existingDoc -> {
                                Uni<String> doKeyToSetUni;
                                Uni<String> mimeTypeToSetUni;
                                final String existingDoKey = existingDoc.getDoKey();
                                final String existingMimeType = existingDoc.getMimeType() != null ? existingDoc.getMimeType() : "application/octet-stream";


                                if (!files.isEmpty() && files.get(0) != null) {
                                    String newFileLocalPath = files.get(0);
                                    String originalFileName = Paths.get(newFileLocalPath).getFileName().toString();
                                    String generatedNewDoKey = user.getUserName() + "/" + UUID.randomUUID() + "-" + originalFileName;
                                    String mimeType = detectMimeType(newFileLocalPath);
                                    mimeTypeToSetUni = wrapToUni(mimeType);

                                    Uni<Void> uploadAndDeleteOldUni = digitalOceanSpacesService.uploadFile(generatedNewDoKey, newFileLocalPath, mimeType);
                                    if (existingDoKey != null && !existingDoKey.isBlank()) {
                                        uploadAndDeleteOldUni = uploadAndDeleteOldUni
                                                .flatMap(v -> digitalOceanSpacesService.deleteFile(existingDoKey)
                                                        .onFailure().recoverWithItem((Void) null)
                                                );
                                    }
                                    doKeyToSetUni = uploadAndDeleteOldUni.map(v -> generatedNewDoKey);
                                } else {
                                    doKeyToSetUni = Uni.createFrom().item(existingDoKey);
                                    mimeTypeToSetUni = Uni.createFrom().item(existingMimeType);
                                }

                                return Uni.combine().all().unis(doKeyToSetUni, mimeTypeToSetUni).asTuple()
                                        .onItem().transformToUni(resolvedDataTuple -> {
                                            String finalDoKeyToSet = resolvedDataTuple.getItem1();
                                            String finalMimeTypeToSet = resolvedDataTuple.getItem2();

                                            LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
                                            String updateSql = String.format("UPDATE %s SET last_mod_user=$1, last_mod_date=$2, " +
                                                            "source=$3, status=$4, type=$5, title=$6, " +
                                                            "artist=$7, genre=$8, album=$9, slug_name=$10, do_key=$11, mime_type=$12 WHERE id=$13;",
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
                                                    .addString(finalDoKeyToSet)
                                                    .addString(finalMimeTypeToSet)
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
        doc.setMimeType(row.getString("mime_type")); // Populate mime_type

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

    private Uni<SoundFragment> executeInsertTransaction(SoundFragment doc, IUser user, LocalDateTime regDate,
                                                        String doKeyForDb, Uni<String> mimeTypeDetectionUni, Uni<Void> fileUploadCompletionUni) {
        return mimeTypeDetectionUni.flatMap(detectedMimeType ->
                        fileUploadCompletionUni.onItem().transformToUni(v -> {
                            String sql = String.format(
                                    "INSERT INTO %s (reg_date, author, last_mod_date, last_mod_user, source, status, type, " +
                                            "title, artist, genre, album, slug_name, do_key, archived, mime_type) " +
                                            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15) RETURNING id;",
                                    entityData.getTableName()
                            );

                            Tuple params = Tuple.of(regDate, user.getId(), regDate, user.getId())
                                    .addString(doc.getSource().name())
                                    .addInteger(doc.getStatus())
                                    .addString(doc.getType().name())
                                    .addString(doc.getTitle())
                                    .addString(doc.getArtist())
                                    .addString(doc.getGenre())
                                    .addString(doc.getAlbum())
                                    .addString(doc.getSlugName())
                                    .addValue(doKeyForDb)
                                    .addInteger(0)
                                    .addValue(detectedMimeType);

                            String readersSql = String.format(
                                    "INSERT INTO %s (reader, entity_id, can_edit, can_delete) VALUES ($1, $2, $3, $4)",
                                    entityData.getRlsName()
                            );

                            return client.withTransaction(tx -> tx.preparedQuery(sql)
                                    .execute(params)
                                    .onItem().transform(result -> result.iterator().next().getUUID("id"))
                                    .onItem().transformToUni(id -> tx.preparedQuery(readersSql)
                                            .execute(Tuple.of(user.getId(), id, true, true))
                                            .onItem().transform(ignored -> id)
                                    )
                            );
                        })
                )
                .onItem().transformToUni(id -> findById(id, user.getId()));
    }

    private String detectMimeType(String filePath) {
        Tika tika = new Tika();
        try {
            String detectedMimeType = tika.detect(Paths.get(filePath));
            if (detectedMimeType == null || detectedMimeType.isEmpty()) {
                LOGGER.warn("Tika could not determine MIME type for file {}. Defaulting to application/octet-stream.", filePath);
                return "application/octet-stream";
            } else {
                return detectedMimeType;
            }
        }catch (IOException e){
            LOGGER.error("Tika could not determine MIME type for file {}. Defaulting to application/octet-stream.", filePath);
            return "application/octet-stream";
        }
    }

    private Uni<String> wrapToUni(String mimeType) {
        return vertx.executeBlocking(Uni.createFrom().emitter(emitter -> {
            try {
                emitter.complete(mimeType);
            } catch (Exception e) {
                LOGGER.error("Unexpected error during MIME type detection for file {}: {}. Defaulting to application/octet-stream.", mimeType, e.getMessage(), e);
                emitter.complete("application/octet-stream");
            }
        }), false);
    }


}