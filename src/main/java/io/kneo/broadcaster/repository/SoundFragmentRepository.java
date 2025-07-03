package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.FileStorageType;
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
import io.kneo.core.repository.exception.UploadAbsenceException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
                        return from(row, true);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }

    public Uni<List<BrandSoundFragment>> findForBrand(UUID brandId, final int limit, final int offset, boolean includeArchived, IUser user) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.last_time_played_by_brand " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bsf.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        // ORDER BY: least played first, then oldest played first, never played at top
        sql += " ORDER BY " +
                "COALESCE(bsf.played_by_brand_count, 0) ASC, " +  // Never played (NULL) becomes 0, so they come first
                "COALESCE(bsf.last_time_played_by_brand, '1970-01-01'::timestamp) ASC"; // Never played gets very old date

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Uni<SoundFragment> soundFragmentUni = from(row, true);
                    return soundFragmentUni.onItem().transform(soundFragment -> {
                        BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                        brandSoundFragment.setId(row.getUUID("id"));
                        brandSoundFragment.setDefaultBrandId(brandId);
                        brandSoundFragment.setPlayedByBrandCount(row.getInteger("played_by_brand_count"));
                        brandSoundFragment.setPlayedTime(row.getLocalDateTime("last_time_played_by_brand"));
                        brandSoundFragment.setSoundFragment(soundFragment);
                        return brandSoundFragment;
                    });
                })
                .concatenate()
                .collect().asList();
    }

    public Uni<List<UUID>> getBrandsForSoundFragment(UUID soundFragmentId, IUser user) {
        String sql = "SELECT bsf.brand_id " +
                "FROM kneobroadcaster__brand_sound_fragments bsf " +
                "JOIN " + entityData.getRlsName() + " rls ON bsf.sound_fragment_id = rls.entity_id " +
                "WHERE bsf.sound_fragment_id = $1 AND rls.reader = $2";

        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("brand_id"))
                .collect().asList();
    }

    public Uni<BrandSoundFragment> populateAllBrands(BrandSoundFragment brandSoundFragment, IUser user) {
        return getBrandsForSoundFragment(brandSoundFragment.getId(), user)
                .onItem().transform(brandIds -> {
                    brandSoundFragment.setRepresentedInBrands(brandIds);
                    return brandSoundFragment;
                });
    }

    public Uni<Integer> findForBrandCount(UUID brandId, boolean includeArchived, IUser user) {
        String sql = "SELECT COUNT(*) " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bsf.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<BrandSoundFragment> findBrandSoundFragmentById(UUID soundFragmentId, IUser user) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.last_time_played_by_brand, bsf.brand_id " +
                "FROM " + entityData.getTableName() + " t " +
                "LEFT JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE t.id = $1 AND rls.reader = $2 " +
                "AND (t.archived IS NULL OR t.archived = 0)";

        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId, user.getId()))
                .onItem().transformToUni(rows -> {
                    if (rows.rowCount() == 0) {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(soundFragmentId));
                    }

                    Row row = rows.iterator().next();
                    return from(row, true)
                            .onItem().transform(soundFragment -> {
                                BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                brandSoundFragment.setId(soundFragment.getId());
                                brandSoundFragment.setSoundFragment(soundFragment);
                                Integer playedCount = row.getInteger("played_by_brand_count");
                                brandSoundFragment.setPlayedByBrandCount(playedCount != null ? playedCount : 0);

                                brandSoundFragment.setPlayedTime(row.getLocalDateTime("last_time_played_by_brand"));
                                brandSoundFragment.setDefaultBrandId(row.getUUID("brand_id"));

                                return brandSoundFragment;
                            });
                });
    }

    public Uni<List<SoundFragment>> search(String searchTerm, final int limit, final int offset, final boolean includeArchived, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            String normalizedTerm = searchTerm.trim().toLowerCase();
            sql += " AND (LOWER(t.title) LIKE '%" + normalizedTerm + "%' OR LOWER(t.artist) LIKE '%" + normalizedTerm + "%')";
        }

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

    public Uni<Integer> getSearchCount(String searchTerm, boolean includeArchived, IUser user) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            String normalizedTerm = searchTerm.trim().toLowerCase();
            sql += " AND (LOWER(t.title) LIKE '%" + normalizedTerm + "%' OR LOWER(t.artist) LIKE '%" + normalizedTerm + "%')";
        }

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<FileMetadata> getFileById(UUID id) {
        String sql = "SELECT f.file_key FROM _files f WHERE f.parent_id = $1";
        return getFileById(id, sql, Tuple.of(id));
    }

    public Uni<FileMetadata> getFileById(UUID id, String slugName, IUser user, boolean includeArchived) {
        String sql = "SELECT f.file_key FROM _files f WHERE f.parent_id = $1 AND f.slug_name = $2";
        return getFileById(id, sql, Tuple.of(id, slugName));
    }

    private Uni<FileMetadata> getFileById(UUID id, String sql, Tuple parameters) {
        return client.preparedQuery(sql)
                .execute(parameters)
                .onItem().invoke(rows ->
                        LOGGER.debug("Query returned {} rows", rows.rowCount()))
                .onFailure().invoke(failure ->
                        LOGGER.error("Database query failed for ID: {} - Error", id, failure)) // Fixed
                .onItem().transformToUni(rows -> {
                    if (rows.rowCount() == 0) {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(
                                "File not found (ID: " + id + ") or access denied"));
                    }

                    Row row = rows.iterator().next();
                    String doKey = row.getString("file_key");
                    LOGGER.debug("Retrieved file key: {} for ID: {}", doKey, id);

                    return fileStorage.retrieveFile(doKey)
                            .onItem().invoke(file ->
                                    LOGGER.debug("Successfully retrieved file content for key: {}", doKey))
                            .onFailure().invoke(failure ->
                                    LOGGER.error("Storage retrieval failed for key: {}", doKey, failure))
                            .onFailure().transform(ex -> {
                                if (ex instanceof FileNotFoundException || ex instanceof DocumentHasNotFoundException) {
                                    LOGGER.warn("File not found in storage - Key: {}", doKey, ex);
                                    return ex;
                                }
                                LOGGER.error("Storage retrieval error - Key: {}", doKey, ex);
                                //TODO it might happen , we need to mark it as archived=2 (corrupted)
                                return new FileNotFoundException(
                                        String.format("Failed to retrieve file (ID: %s, Key: %s) - Cause: %s",
                                                id, doKey, ex.getMessage()));
                            });
                })
                .onFailure(FileNotFoundException.class)
                .invoke(fnf -> LOGGER.error("File not found flow", fnf))
                .onFailure().invoke(failure ->
                        LOGGER.error("Unexpected failure processing ID: {}", id, failure))
                .onFailure().recoverWithUni(otherException -> {
                    if (otherException instanceof DocumentHasNotFoundException) {
                        LOGGER.warn("Document not found", otherException);
                        return Uni.createFrom().failure(otherException);
                    }
                    LOGGER.error("Unexpected error", otherException);
                    return Uni.createFrom().failure(new RuntimeException(
                            "Unexpected error fetching file data for ID: " + id, otherException));
                })
                .onTermination().invoke((res, fail, cancelled) -> {
                    if (cancelled) {
                        LOGGER.warn("Operation cancelled - ID: {}", id);
                    }
                    if (fail != null) {
                        LOGGER.error("Operation failed", fail);
                    }
                    if (res != null) {
                        LOGGER.debug("Operation succeeded - ID: {}", id);
                    }
                });
    }

    public Uni<SoundFragment> insert(SoundFragment doc, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();
        final List<FileMetadata> originalFiles = doc.getFileMetadataList();

        final List<FileMetadata> filesToProcess = (originalFiles != null && !originalFiles.isEmpty())
                ? List.of(originalFiles.get(0))
                : null;

        if (filesToProcess != null && !filesToProcess.isEmpty()) {
            doc.setSource(SourceType.USERS_UPLOAD);
            FileMetadata meta = filesToProcess.get(0);
            Path filePath = meta.getFilePath();
            if (filePath == null) {
                throw new IllegalArgumentException("File metadata contains an entry with a null file path.");
            }
            if (!Files.exists(filePath)) {
                throw new UploadAbsenceException("Upload file not found at path: " + filePath);
            }
            meta.setFileOriginalName(filePath.getFileName().toString());
            meta.setSlugName(WebHelper.generateSlug(doc.getArtist(), doc.getTitle()));
            String doKey = WebHelper.generateSlugPath(doc.getGenre().toLowerCase(), doc.getArtist(), String.valueOf(UUID.randomUUID()));
            meta.setFileKey(doKey);
            meta.setMimeType(detectMimeType(filePath.toString()));
            doc.setFileMetadataList(filesToProcess);
        }

        return executeInsertTransaction(doc, user, nowTime, Uni.createFrom().voidItem())
                .onItem().transformToUni(insertedDoc -> {
                    if (filesToProcess != null && !filesToProcess.isEmpty()) {
                        FileMetadata meta = filesToProcess.get(0);
                        return fileStorage.storeFile(
                                        meta.getFileKey(),
                                        meta.getFilePath().toString(),
                                        meta.getMimeType(),
                                        entityData.getTableName(),
                                        insertedDoc.getId()
                                )
                                .onItem().invoke(storedKey -> LOGGER.debug("File stored with key: {} for doc ID: {}", storedKey, insertedDoc.getId()))
                                .onItem().transform(ignored -> insertedDoc)
                                .onFailure().recoverWithUni(ex -> {
                                    LOGGER.error("File failed to store for doc ID: {}. DB record was created.", insertedDoc.getId(), ex);
                                    return Uni.createFrom().failure(new RuntimeException("File storage failed after sound fragment creation", ex));
                                });
                    }
                    return Uni.createFrom().item(insertedDoc);
                });
    }

    public Uni<SoundFragment> update(UUID id, SoundFragment doc, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }

                    return findById(id, user.getId(), true)
                            .onItem().transformToUni(existingDoc -> {
                                final List<FileMetadata> originalFiles = doc.getFileMetadataList();

                                final List<FileMetadata> newFiles = (originalFiles != null && !originalFiles.isEmpty())
                                        ? List.of(originalFiles.get(0))
                                        : null;

                                Uni<Void> fileStoredUni = Uni.createFrom().voidItem();

                                if (newFiles != null) {
                                    FileMetadata meta = newFiles.get(0);
                                    if (meta.getFilePath() != null) {
                                        String localPath = meta.getFilePath().toString();
                                        Path path = Paths.get(localPath);
                                        if (!Files.exists(path)) {
                                            return Uni.createFrom().failure(new UploadAbsenceException("Upload file not found at path: " + localPath));
                                        }

                                        String doKey = WebHelper.generateSlugPath(doc.getGenre().toLowerCase(), doc.getArtist(), String.valueOf(UUID.randomUUID()));
                                        meta.setFileKey(doKey);
                                        String mimeType = detectMimeType(localPath);
                                        meta.setMimeType(mimeType);
                                        meta.setFileOriginalName(path.getFileName().toString());
                                        meta.setSlugName(WebHelper.generateSlug(doc.getArtist(), doc.getTitle()));

                                        LOGGER.info("DEBUG: About to store file - Key: {}, Path: {},  Artist: {}, Title: {}",
                                                doKey, localPath, doc.getArtist(), doc.getTitle());

                                        fileStoredUni = fileStorage.storeFile(doKey, localPath, mimeType, entityData.getTableName(), id)
                                                .onItem().invoke(storedKey -> LOGGER.info("DEBUG: File stored with key: {} for doc ID: {}", storedKey, id))
                                                .onFailure().invoke(ex -> LOGGER.error("Failed to store file with key: {}", doKey, ex))
                                                .onItem().ignore().andContinueWithNull();
                                    }
                                }

                                return fileStoredUni.onItem().transformToUni(ignored -> {
                                    LocalDateTime nowTime = ZonedDateTime.now(java.time.ZoneOffset.UTC).toLocalDateTime();

                                    return client.withTransaction(tx -> {
                                        String deleteSql = String.format("DELETE FROM _files WHERE parent_id = $1 AND parent_table = '%s'", entityData.getTableName());
                                        Uni<Void> deleteUni = tx.preparedQuery(deleteSql).execute(Tuple.of(id)).onItem().ignore().andContinueWithNull();

                                        return deleteUni.onItem().transformToUni(v -> {
                                            if (newFiles != null) {
                                                String filesSql = "INSERT INTO _files (parent_table, parent_id, storage_type, " +
                                                        "mime_type, file_original_name, file_key, file_bin, slug_name) " +
                                                        "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";
                                                // Process only the single file
                                                FileMetadata meta = newFiles.get(0);
                                                Tuple fileParams = Tuple.of(
                                                                entityData.getTableName(),
                                                                id,
                                                                FileStorageType.DIGITAL_OCEAN,
                                                                meta.getMimeType(),
                                                                meta.getFileOriginalName(),
                                                                meta.getFileKey()
                                                        )
                                                        .addValue(meta.getFileBin())
                                                        .addValue(meta.getSlugName());
                                                return tx.preparedQuery(filesSql).execute(fileParams).onItem().ignore().andContinueWithNull();
                                            }
                                            return Uni.createFrom().voidItem();
                                        }).onItem().transformToUni(v -> {
                                            String updateSql = String.format("UPDATE %s SET last_mod_user=$1, last_mod_date=$2, " +
                                                            "source=$3, status=$4, type=$5, title=$6, " +
                                                            "artist=$7, genre=$8, album=$9, slug_name=$10 WHERE id=$11;",
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
                                                    .addUUID(id);

                                            return tx.preparedQuery(updateSql).execute(params);
                                        });
                                    }).onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return findById(id, user.getId(), true);
                                    });
                                });
                            });
                });
    }

    public Uni<Integer> archive(UUID id, IUser user) {
        return archive(id, entityData, user);
    }

    public Uni<Integer> delete(UUID uuid, IUser user) {
        return findById(uuid, user.getId(), true)
                .onItem().transformToUni(doc -> {

                    String getKeysSql = "SELECT file_key FROM _files WHERE parent_id = $1";
                    return client.preparedQuery(getKeysSql).execute(Tuple.of(uuid))
                            .onItem().transformToUni(rows -> {
                                List<String> keysToDelete = new ArrayList<>();
                                rows.forEach(row -> {
                                    String key = row.getString("file_key");
                                    if (key != null && !key.isBlank()) {
                                        keysToDelete.add(key);
                                    }
                                });

                                List<Uni<Void>> deleteFileUnis = keysToDelete.stream()
                                        .map(key -> fileStorage.deleteFile(key)
                                                .onFailure().recoverWithUni(e -> {
                                                    LOGGER.error("Failed to delete file {} from storage for SoundFragment {}. DB record deletion will proceed.", key, uuid, e);
                                                    return Uni.createFrom().voidItem();
                                                })
                                        ).collect(Collectors.toList());

                                return Uni.combine().all().unis(deleteFileUnis).discardItems();

                            }).onItem().transformToUni(v -> {
                                return client.withTransaction(tx -> {
                                    String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                                    String deleteFilesSql = "DELETE FROM _files WHERE parent_id = $1";
                                    String deleteDocSql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                                    Uni<RowSet<Row>> rlsDelete = tx.preparedQuery(deleteRlsSql).execute(Tuple.of(uuid));
                                    Uni<RowSet<Row>> filesDelete = tx.preparedQuery(deleteFilesSql).execute(Tuple.of(uuid));

                                    return Uni.combine().all().unis(rlsDelete, filesDelete)
                                            .discardItems()
                                            .onItem().transformToUni(ignored -> tx.preparedQuery(deleteDocSql).execute(Tuple.of(uuid)))
                                            .onItem().transform(RowSet::rowCount);
                                });
                            });
                });
    }

    private Uni<SoundFragment> from(Row row, boolean addAttachedFileMetadata) {
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

        if (addAttachedFileMetadata) {
            String fileQuery = "SELECT id, reg_date, last_mod_date, parent_table, parent_id, archived, archived_date," +
                    " storage_type, mime_type, slug_name, file_original_name, file_key, file_bin FROM _files" +
                    " WHERE parent_table = '" + entityData.getTableName() + "' AND parent_id = $1 AND archived = 0 ORDER BY reg_date ASC";

            return client.preparedQuery(fileQuery)
                    .execute(Tuple.of(doc.getId()))
                    .onItem().transform(rowSet -> {
                        List<FileMetadata> files = new ArrayList<>();
                        for (Row fileRow : rowSet) {
                            FileMetadata fileMetadata = new FileMetadata();
                            fileMetadata.setId(fileRow.getLong("id"));
                            fileMetadata.setRegDate(fileRow.getLocalDateTime("reg_date").atZone(ZoneId.systemDefault()));
                            fileMetadata.setLastModifiedDate(fileRow.getLocalDateTime("last_mod_date").atZone(ZoneId.systemDefault()));
                            fileMetadata.setParentTable(fileRow.getString("parent_table"));
                            fileMetadata.setParentId(fileRow.getUUID("parent_id"));
                            fileMetadata.setArchived(fileRow.getInteger("archived"));
                            if (fileRow.getLocalDateTime("archived_date") != null) {
                                fileMetadata.setArchivedDate(fileRow.getLocalDateTime("archived_date"));
                            }
                            fileMetadata.setFileStorageType(FileStorageType.valueOf(fileRow.getString("storage_type")));
                            fileMetadata.setMimeType(fileRow.getString("mime_type"));
                            fileMetadata.setSlugName(fileRow.getString("slug_name"));
                            fileMetadata.setFileOriginalName(fileRow.getString("file_original_name"));
                            fileMetadata.setFileKey(fileRow.getString("file_key"));
                            files.add(fileMetadata);
                        }
                        doc.setFileMetadataList(files);
                        return doc;
                    });
        }
        return Uni.createFrom().item(doc);
    }

    private Uni<SoundFragment> executeInsertTransaction(SoundFragment doc, IUser user, LocalDateTime regDate,
                                                        Uni<Void> fileUploadCompletionUni) {
        return fileUploadCompletionUni.onItem().transformToUni(v -> {
            String sql = String.format(
                    "INSERT INTO %s (reg_date, author, last_mod_date, last_mod_user, source, status, type, " +
                            "title, artist, genre, album, slug_name) " +
                            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) RETURNING id;",
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
                    .addString(doc.getSlugName());

            return client.withTransaction(tx -> tx.preparedQuery(sql)
                    .execute(params)
                    .onItem().transform(result -> result.iterator().next().getUUID("id"))
                    .onItem().transformToUni(id -> {
                        Uni<Void> fileMetadataUni = Uni.createFrom().voidItem();
                        if (doc.getFileMetadataList() != null && !doc.getFileMetadataList().isEmpty()) {
                            String filesSql = "INSERT INTO _files (parent_table, parent_id, storage_type, mime_type, file_original_name, file_key, file_bin, slug_name) " +
                                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";
                            List<Tuple> filesParams = doc.getFileMetadataList().stream()
                                    .map(meta -> Tuple.of(
                                                            entityData.getTableName(),
                                                            id,
                                                            FileStorageType.DIGITAL_OCEAN,
                                                            meta.getMimeType(),
                                                            meta.getFileOriginalName(),
                                                            meta.getFileKey()
                                                    )
                                                    .addValue(meta.getFileBin())
                                                    .addValue(meta.getSlugName())
                                    ).collect(Collectors.toList());
                            fileMetadataUni = tx.preparedQuery(filesSql).executeBatch(filesParams).onItem().ignore().andContinueWithNull();
                        }

                        String readersSql = String.format(
                                "INSERT INTO %s (reader, entity_id, can_edit, can_delete) VALUES ($1, $2, $3, $4)",
                                entityData.getRlsName()
                        );

                        return fileMetadataUni
                                .onItem().transformToUni(ignored -> tx.preparedQuery(readersSql)
                                        .execute(Tuple.of(user.getId(), id, true, true))
                                )
                                .onItem().transform(ignored -> id);
                    })
            );
        }).onItem().transformToUni(id -> findById(id, user.getId(), true));
    }
}