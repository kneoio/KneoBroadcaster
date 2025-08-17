package io.kneo.broadcaster.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.SoundFragmentFilter;
import io.kneo.broadcaster.model.cnst.FileStorageType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.file.HetznerStorage;
import io.kneo.broadcaster.repository.file.IFileStorage;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.broadcaster.util.WebHelper;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
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
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.SqlResult;
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
    private final SoundFragmentFileHandler fileHandler;
    private final SoundFragmentQueryBuilder queryBuilder;
    private final SoundFragmentBrandAssociationHandler brandHandler;

    @Inject
    public SoundFragmentRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository,
                                   HetznerStorage fileStorage, SoundFragmentFileHandler fileHandler,
                                   SoundFragmentQueryBuilder queryBuilder, SoundFragmentBrandAssociationHandler brandHandler) {
        super(client, mapper, rlsRepository);
        this.fileStorage = fileStorage;
        this.fileHandler = fileHandler;
        this.queryBuilder = queryBuilder;
        this.brandHandler = brandHandler;
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset, final boolean includeArchived,
                                           final IUser user, final SoundFragmentFilter filter) {
        String sql = queryBuilder.buildGetAllQuery(entityData.getTableName(), entityData.getRlsName(),
                user, includeArchived, filter, limit, offset);
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, SoundFragmentFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        if (filter != null && filter.isActivated()) {
            sql += queryBuilder.buildFilterConditions(filter);
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<List<SoundFragment>> search(String searchTerm, final int limit, final int offset,
                                           final boolean includeArchived, final IUser user,
                                           final SoundFragmentFilter filter) {
        String sql = queryBuilder.buildSearchQuery(entityData.getTableName(), entityData.getRlsName(),
                searchTerm, includeArchived, filter, limit, offset);

        List<Object> params = new ArrayList<>();
        params.add(user.getId());

        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            String normalizedTerm = "%" + searchTerm.trim().toLowerCase() + "%";
            params.add(normalizedTerm);
            params.add(normalizedTerm);
            params.add(normalizedTerm);
            params.add(normalizedTerm);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.from(params))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<FileMetadata> getFirstFile(UUID id) {
        return fileHandler.getFirstFile(id)
                .onFailure(DocumentHasNotFoundException.class)
                .recoverWithUni(ex -> {
                    markAsCorrupted(id).subscribe().with(
                            result -> LOGGER.info("Marked file {} as corrupted due to missing record", id),
                            failure -> LOGGER.error("Failed to mark file {} as corrupted", id, failure)
                    );
                    return Uni.createFrom().failure(ex);
                })
                .onFailure(FileNotFoundException.class)
                .recoverWithUni(ex -> {
                    markAsCorrupted(id).subscribe().with(
                            result -> LOGGER.info("Marked file {} as corrupted due to retrieval failure", id),
                            failure -> LOGGER.error("Failed to mark file {} as corrupted", id, failure)
                    );
                    return Uni.createFrom().failure(ex);
                });
    }

    public Uni<FileMetadata> getFileBySlugName(UUID id, String slugName, IUser user, boolean includeArchived) {
        return fileHandler.getFileBySlugName(id, slugName)
                .onFailure().recoverWithUni(ex -> {
                    markAsCorrupted(id).subscribe().with(
                            result -> LOGGER.info("Marked file {} as corrupted", id),
                            failure -> LOGGER.error("Failed to mark file {} as corrupted", id, failure)
                    );
                    return Uni.createFrom().failure(ex);
                });
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

    public Uni<SoundFragment> insert(SoundFragment doc, List<UUID> representedInBrands, IUser user) {
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

        return executeInsertTransaction(doc, user, nowTime, Uni.createFrom().voidItem(), representedInBrands)
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

    public Uni<Integer> markAsCorrupted(UUID uuid) {
        return markAsCorrupted(uuid, SuperUser.build());
    }

    public Uni<Integer> markAsCorrupted(UUID uuid, IUser user) {
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


    private Uni<SoundFragment> executeInsertTransaction(SoundFragment doc, IUser user, LocalDateTime regDate,
                                                        Uni<Void> fileUploadCompletionUni, List<UUID> representedInBrands) {
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
                        Uni<Void> fileMetadataUni = insertFileMetadata(tx, id, doc);
                        return fileMetadataUni
                                .onItem().transformToUni(ignored -> insertRLSPermissions(tx, id, entityData, user))
                                .onItem().transformToUni(ignored -> brandHandler.insertBrandAssociations(tx, id, representedInBrands, user))
                                .onItem().transform(ignored -> id);
                    })
            );
        }).onItem().transformToUni(id -> findById(id, user.getId(), true));
    }

    private Uni<Void> insertFileMetadata(SqlClient tx, UUID id, SoundFragment doc) {
        if (doc.getFileMetadataList() == null || doc.getFileMetadataList().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String filesSql = "INSERT INTO _files (parent_table, parent_id, storage_type, mime_type, file_original_name, file_key, file_bin, slug_name) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";
        List<Tuple> filesParams = doc.getFileMetadataList().stream()
                .map(meta -> Tuple.of(
                                        entityData.getTableName(),
                                        id,
                                        FileStorageType.HETZNER,
                                        meta.getMimeType(),
                                        meta.getFileOriginalName(),
                                        meta.getFileKey()
                                )
                                .addValue(meta.getFileBin())
                                .addValue(meta.getSlugName())
                ).collect(Collectors.toList());

        return tx.preparedQuery(filesSql).executeBatch(filesParams).onItem().ignore().andContinueWithNull();
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

                        // Mark as corrupted if no files found when files are expected
                        if (files.isEmpty()) {
                            markAsCorrupted(doc.getId()).subscribe().with(
                                    result -> LOGGER.info("Marked SoundFragment {} as corrupted due to missing files", doc.getId()),
                                    failure -> LOGGER.error("Failed to mark SoundFragment {} as corrupted", doc.getId(), failure)
                            );
                        }

                        return doc;
                    });
        }
        return Uni.createFrom().item(doc);
    }


    public Uni<List<BrandSoundFragment>> findForBrand(UUID brandId, final int limit, final int offset,
                                                      boolean includeArchived, IUser user, SoundFragmentFilter filter) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.last_time_played_by_brand " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bsf.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        sql += " ORDER BY " +
                "bsf.played_by_brand_count ASC, " +
                "COALESCE(bsf.last_time_played_by_brand, '1970-01-01'::timestamp) ASC";

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

    public Uni<Integer> findForBrandCount(UUID brandId, boolean includeArchived, IUser user, SoundFragmentFilter filter) {
        String sql = "SELECT COUNT(*) " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bsf.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId()))
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
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


    public Uni<SoundFragment> update(UUID id, SoundFragment doc, List<UUID> representedInBrands, IUser user) {
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

                                Uni<Void> fileStoredUni = handleFileUpdate(id, doc, newFiles);

                                return fileStoredUni.onItem().transformToUni(ignored -> {
                                    LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

                                    return client.withTransaction(tx -> {
                                        return deleteExistingFiles(tx, id)
                                                .onItem().transformToUni(v -> insertNewFiles(tx, id, newFiles))
                                                .onItem().transformToUni(v -> brandHandler.updateBrandAssociations(tx, id, representedInBrands, user))
                                                .onItem().transformToUni(v -> updateSoundFragmentRecord(tx, id, doc, user, nowTime));
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

    private Uni<Void> handleFileUpdate(UUID id, SoundFragment doc, List<FileMetadata> newFiles) {
        if (newFiles == null) {
            return Uni.createFrom().voidItem();
        }

        FileMetadata meta = newFiles.get(0);
        if (meta.getFilePath() == null) {
            return Uni.createFrom().voidItem();
        }

        String localPath = meta.getFilePath().toString();
        Path path = Paths.get(localPath);
        if (!Files.exists(path)) {
            return Uni.createFrom().failure(new UploadAbsenceException("Upload file not found at path: " + localPath));
        }

        String doKey = WebHelper.generateSlugPath(doc.getGenre().toLowerCase(), doc.getArtist(), String.valueOf(UUID.randomUUID()));
        meta.setFileKey(doKey);
        meta.setMimeType(detectMimeType(localPath));
        meta.setFileOriginalName(path.getFileName().toString());
        meta.setSlugName(WebHelper.generateSlug(doc.getArtist(), doc.getTitle()));

        LOGGER.debug("Storing file - Key: {}, Path: {}, Artist: {}, Title: {}", doKey, localPath, doc.getArtist(), doc.getTitle());

        return fileStorage.storeFile(doKey, localPath, meta.getMimeType(), entityData.getTableName(), id)
                .onItem().invoke(storedKey -> LOGGER.debug("File stored with key: {} for doc ID: {}", storedKey, id))
                .onFailure().invoke(ex -> LOGGER.error("Failed to store file with key: {}", doKey, ex))
                .onItem().ignore().andContinueWithNull();
    }

    private Uni<Void> deleteExistingFiles(SqlClient tx, UUID id) {
        String deleteSql = String.format("DELETE FROM _files WHERE parent_id = $1 AND parent_table = '%s'", entityData.getTableName());
        return tx.preparedQuery(deleteSql).execute(Tuple.of(id)).onItem().ignore().andContinueWithNull();
    }

    private Uni<Void> insertNewFiles(SqlClient tx, UUID id, List<FileMetadata> newFiles) {
        if (newFiles == null) {
            return Uni.createFrom().voidItem();
        }

        String filesSql = "INSERT INTO _files (parent_table, parent_id, storage_type, " +
                "mime_type, file_original_name, file_key, file_bin, slug_name) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";
        FileMetadata meta = newFiles.get(0);
        Tuple fileParams = Tuple.of(
                        entityData.getTableName(),
                        id,
                        FileStorageType.HETZNER,
                        meta.getMimeType(),
                        meta.getFileOriginalName(),
                        meta.getFileKey()
                )
                .addValue(meta.getFileBin())
                .addValue(meta.getSlugName());

        return tx.preparedQuery(filesSql).execute(fileParams).onItem().ignore().andContinueWithNull();
    }

    private Uni<RowSet<Row>> updateSoundFragmentRecord(SqlClient tx, UUID id, SoundFragment doc, IUser user, LocalDateTime nowTime) {
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
    }

    public Uni<Integer> getSearchCount(String searchTerm, boolean includeArchived, IUser user, SoundFragmentFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            String normalizedTerm = searchTerm.trim().toLowerCase();
            sql += " AND (LOWER(t.title) LIKE '%" + normalizedTerm + "%' OR LOWER(t.artist) LIKE '%" + normalizedTerm + "%' OR LOWER(t.genre) LIKE '%" + normalizedTerm + "%' OR CAST(t.id AS TEXT) LIKE '%" + normalizedTerm + "%')";
        }

        if (!includeArchived) {
            sql += " AND (t.archived IS NULL OR t.archived = 0)";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    private String buildFilterConditions(SoundFragmentFilter filter) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getGenres() != null && !filter.getGenres().isEmpty()) {
            conditions.append(" AND t.genre IN (");
            for (int i = 0; i < filter.getGenres().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getGenres().get(i).replace("'", "''")).append("'");
            }
            conditions.append(")");
        }

        if (filter.getSources() != null && !filter.getSources().isEmpty()) {
            conditions.append(" AND t.source IN (");
            for (int i = 0; i < filter.getSources().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getSources().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        if (filter.getTypes() != null && !filter.getTypes().isEmpty()) {
            conditions.append(" AND t.type IN (");
            for (int i = 0; i < filter.getTypes().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getTypes().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        return conditions.toString();
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}