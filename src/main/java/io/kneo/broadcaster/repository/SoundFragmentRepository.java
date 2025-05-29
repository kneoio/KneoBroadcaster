package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileData;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.file.DigitalOceanStorage;
import io.kneo.broadcaster.repository.file.IFileStorage;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.broadcaster.util.WebHelper;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
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
    private final IFileStorage fileStorage;

    @Inject
    public SoundFragmentRepository(PgPool client,
                                   ObjectMapper mapper,
                                   RLSRepository rlsRepository, DigitalOceanStorage fileStorage
    ) {
        super(client, mapper, rlsRepository);
        this.fileStorage = fileStorage;
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset, final boolean includeArchived, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        sql += " ORDER BY t.last_mod_date DESC";

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

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<SoundFragment> findById(UUID uuid, Long userID, boolean includeArchived) {
        String sql = "SELECT theTable.*, rls.* " +
                "FROM %s theTable " +
                "JOIN %s rls ON theTable.id = rls.entity_id " +
                "WHERE rls.reader = $1 AND theTable.id = $2";

        if (!includeArchived) {
            sql += " AND (theTable.archived IS NULL OR theTable.archived = 0)";
        }

        return client.preparedQuery(String.format(sql, entityData.getTableName(), entityData.getRlsName()))
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, false);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }

    public Uni<List<BrandSoundFragment>> findForBrand(UUID brandId, final int limit, final int offset, boolean includeArchived) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.last_time_played_by_brand " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1";

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        sql += " ORDER BY played_by_brand_count";

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

    public Uni<FileData> getFileById(UUID fileId, Long userId, boolean includeArchived) {
        String sql = "SELECT sf.artist, sf.album, sf.title, sf.mime_type " +
                "FROM " + entityData.getTableName() + " sf " +
                "JOIN " + entityData.getRlsName() + " rls ON sf.id = rls.entity_id " +
                "WHERE sf.id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (sf.archived IS NULL OR sf.archived = 0)";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(fileId, userId))
                .onItem().transformToUni(rows -> {
                    if (rows.rowCount() == 0) {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(
                                String.format("File not found (ID: %s) or access denied.", fileId)
                        ));
                    }
                    Row row = rows.iterator().next();
                    String artist = row.getString("artist");
                    String album = row.getString("album");
                    String title = row.getString("title");
                    String mimeType = row.getString("mime_type");
                    String doKey = WebHelper.generateSlugPath(artist, album, title);

                    return fileStorage.retrieveFile(doKey)
                            .onItem().transform(fileMetadata -> new FileData(fileMetadata.getFileBin(), mimeType))
                            .onFailure().transform(ex -> {
                                if (ex instanceof FileNotFoundException || ex instanceof DocumentHasNotFoundException) {
                                    return ex;
                                }
                                return new FileNotFoundException(
                                        String.format("Failed to retrieve file content from storage for ID: %s. Derived Key: %s. Cause: %s", fileId, doKey, ex.getMessage())
                                );
                            });
                })
                .onFailure(FileNotFoundException.class).recoverWithUni(fnfException -> Uni.createFrom().failure(fnfException))
                .onFailure().recoverWithUni(otherException -> {
                    if (otherException instanceof DocumentHasNotFoundException) {
                        return Uni.createFrom().failure(otherException);
                    }
                    return Uni.createFrom().failure(new RuntimeException(
                            String.format("An unexpected error occurred while fetching file data for ID: %s.", fileId), otherException
                    ));
                });
    }

    public Uni<SoundFragment> insert(SoundFragment doc, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        doc.setDoKey(getDoKey(doc));
        Uni<String> mimeTypeUni = Uni.createFrom().item((String) null);
        Uni<Void> uploadUni = Uni.createFrom().voidItem();
        return executeInsertTransaction(doc, user, nowTime, mimeTypeUni, uploadUni);
    }

    public Uni<SoundFragment> insert(SoundFragment doc, List<String> files, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        Uni<String> mimeTypeUni = Uni.createFrom().item((String) null);

        String derivedKey = getDoKey(doc);
        doc.setDoKey(derivedKey);
        String pathCandidate = null;
        if (files != null && !files.isEmpty()) {
            pathCandidate = files.get(0);
        }

        String detectedMimeType = null;
        if (pathCandidate != null && !pathCandidate.trim().isEmpty()) {
            String actualFileToUploadPath = pathCandidate.trim();
            detectedMimeType = detectMimeType(actualFileToUploadPath);
            mimeTypeUni = wrapToUni(detectedMimeType);
        }

        // First execute the database insert without file upload
        String finalDetectedMimeType = detectedMimeType;
        String finalPathCandidate = pathCandidate;

        return executeInsertTransaction(doc, user, nowTime, mimeTypeUni, Uni.createFrom().voidItem())
                .onItem().transformToUni(insertedDoc -> {
                    // Now we have the ID, store the file with parent_table and parent_id
                    if (finalPathCandidate != null && !finalPathCandidate.trim().isEmpty()) {
                        return fileStorage.storeFile(derivedKey, finalPathCandidate.trim(), finalDetectedMimeType,
                                        entityData.getTableName(), insertedDoc.getId())
                                .onItem().transformToUni(storedKey -> {
                                    LOGGER.debug("File stored successfully with key: {} for doc ID: {}", storedKey, insertedDoc.getId());
                                    return Uni.createFrom().item(insertedDoc);
                                })
                                .onFailure().recoverWithUni(ex -> {
                                    LOGGER.error("Failed to store file with key: {} for doc ID: {}", derivedKey, insertedDoc.getId(), ex);
                                    return Uni.createFrom().failure(new RuntimeException("File storage failed after sound fragment creation", ex));
                                });
                    } else {
                        return Uni.createFrom().item(insertedDoc);
                    }
                });
    }

    public Uni<SoundFragment> update(UUID id, SoundFragment doc, List<String> files, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }

                    return findById(id, user.getId(), true)
                            .onItem().transformToUni(existingDoc -> {
                                Uni<String> mimeTypeToSetUni;
                                final String currentMimeTypeInDb = existingDoc.getMimeType() != null ? existingDoc.getMimeType() : "application/octet-stream";

                                String doKey = getDoKey(doc);
                                doc.setDoKey(doKey);
                                Uni<Void> doUploadOperation = Uni.createFrom().voidItem();

                                if (files != null && !files.isEmpty() && files.get(0) != null) {
                                    String newFileLocalPath = files.get(0).trim();
                                    String detectedMimeType = detectMimeType(newFileLocalPath);
                                    mimeTypeToSetUni = wrapToUni(detectedMimeType);
                                    //doUploadOperation = digitalOceanSpacesService.uploadFile(doKey, newFileLocalPath, detectedMimeType);
                                    // In the update method, replace the digitalOceanSpacesService.uploadFile call with:
                                    doUploadOperation = fileStorage.storeFile(doKey, newFileLocalPath, detectedMimeType,
                                                    entityData.getTableName(), id)
                                            .onItem().transformToUni(storedKey -> {
                                                LOGGER.debug("File updated successfully with key: {} for doc ID: {}", storedKey, id);
                                                return Uni.createFrom().voidItem();
                                            })
                                            .onFailure().recoverWithUni(ex -> {
                                                LOGGER.error("Failed to update file with key: {} for doc ID: {}", doKey, id, ex);
                                                return Uni.createFrom().failure(new RuntimeException("File update failed", ex));
                                            });
                                } else {
                                    mimeTypeToSetUni = Uni.createFrom().item(currentMimeTypeInDb);
                                }

                                return Uni.combine().all().unis(doUploadOperation.map(v -> doKey), mimeTypeToSetUni).asTuple()
                                        .onItem().transformToUni(resolvedDataTuple -> {
                                            String ignoredDoKey = resolvedDataTuple.getItem1();
                                            String mimeTypeForDb = resolvedDataTuple.getItem2();

                                            LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
                                            String updateSql = String.format("UPDATE %s SET last_mod_user=$1, last_mod_date=$2, " +
                                                            "source=$3, status=$4, type=$5, title=$6, " +
                                                            "artist=$7, genre=$8, album=$9, slug_name=$10, mime_type=$11 WHERE id=$12;",
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
                                                    .addString(mimeTypeForDb)
                                                    .addUUID(id);

                                            return client.withTransaction(tx -> tx.preparedQuery(updateSql)
                                                    .execute(params)
                                                    .onItem().transformToUni(rowSet -> {
                                                        if (rowSet.rowCount() == 0) {
                                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                                        }
                                                        return findById(id, user.getId(), true);
                                                    }));
                                        });
                            });
                });
    }

    public Uni<Integer> archive(UUID uuid, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), uuid)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), uuid));
                    }

                    String sql = String.format("UPDATE %s SET archived = 1, last_mod_date = $1, last_mod_user = $2 WHERE id = $3",
                            entityData.getTableName());

                    return client.preparedQuery(sql)
                            .execute(Tuple.of(ZonedDateTime.now().toLocalDateTime(), user.getId(), uuid))
                            .onItem().transform(RowSet::rowCount);
                });
    }

    public Uni<Integer> delete(UUID uuid, IUser user) {
        return findById(uuid, user.getId(), true)
                .onItem().transformToUni(doc -> {
                    final String doKey = getDoKey(doc);
                    Uni<Void> deleteFileUni = Uni.createFrom().voidItem();

                    if (doKey != null && !doKey.isBlank()) {
                        // Use fileStorage instead of digitalOceanSpacesService directly
                        deleteFileUni = fileStorage.deleteFile(doKey)
                                .onFailure().recoverWithUni(e -> {
                                    LOGGER.error("Failed to delete file {} from storage for SoundFragment {}. DB record deletion will proceed.", doKey, uuid, e);
                                    return Uni.createFrom().voidItem();
                                });
                    }
                    return deleteFileUni.onItem().transformToUni(v -> super.delete(uuid, entityData, user));
                });
    }

    private Uni<SoundFragment> from(Row row, boolean downloadFile) {
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
        doc.setDoKey(getDoKey(doc));
        doc.setSlugName(row.getString("slug_name"));

        if (row.getString("description") != null) {
            doc.setDescription(row.getString("description"));
        }

        if (downloadFile) {
            final String keyToFetch = doc.getDoKey();
            return fileStorage.retrieveFile(keyToFetch)
                    .onItem().transformToUni(fileMetadata -> {
                        doc.setFilePath(fileMetadata.getFilePath());
                        doc.setFileMetadataList(List.of(fileMetadata));
                        return Uni.createFrom().item(doc);
                    })
                    .onFailure().recoverWithUni(e -> {
                        LOGGER.warn("Failed to fetch file from storage for key {}: {}. SoundFragment will not have filePath.", keyToFetch, e.getMessage());
                        return Uni.createFrom().item(doc);
                    });
        }
        return Uni.createFrom().item(doc);
    }


    private Uni<SoundFragment> executeInsertTransaction(SoundFragment doc, IUser user, LocalDateTime regDate,
                                                        Uni<String> mimeTypeUni, Uni<Void> fileUploadCompletionUni) {
        return mimeTypeUni.flatMap(detectedMimeType ->
                        fileUploadCompletionUni.onItem().transformToUni(v -> {
                            String sql = String.format(
                                    "INSERT INTO %s (reg_date, author, last_mod_date, last_mod_user, source, status, type, " +
                                            "title, artist, genre, album, slug_name, archived, mime_type) " +
                                            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) RETURNING id;",
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
                .onItem().transformToUni(id -> findById(id, user.getId(), true));
    }

    //TODO should be moved to 2next
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
        } catch (IOException e) {
            LOGGER.error("Tika could not determine MIME type for file {}. Defaulting to application/octet-stream.", filePath);
            return "application/octet-stream";
        }
    }

    private Uni<String> wrapToUni(String detectedMimeType) {
        return Uni.createFrom().item(detectedMimeType);
    }

    private static String getDoKey(SoundFragment doc) {
        return WebHelper.generateSlugPath(doc.getArtist(), doc.getAlbum(), doc.getTitle());
    }
}