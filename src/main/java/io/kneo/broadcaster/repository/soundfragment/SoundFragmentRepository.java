package io.kneo.broadcaster.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.cnst.FileStorageType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.BrandSoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragmentFilter;
import io.kneo.broadcaster.repository.file.HetznerStorage;
import io.kneo.broadcaster.repository.file.IFileStorage;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.exception.UploadAbsenceException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SOUND_FRAGMENT;

@ApplicationScoped
public class SoundFragmentRepository extends SoundFragmentRepositoryAbstract {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SOUND_FRAGMENT);

    private final IFileStorage fileStorage;
    private final SoundFragmentFileHandler fileHandler;
    private final SoundFragmentQueryBuilder queryBuilder;
    private final SoundFragmentBrandAssociationHandler brandHandler;
    private final SecureRandom secureRandom = new SecureRandom();

    public SoundFragmentRepository() {
        super();
        this.fileStorage = null;
        this.fileHandler = null;
        this.queryBuilder = null;
        this.brandHandler = null;
    }

    public Uni<List<BrandSoundFragment>> getForBrandBySimilarity(UUID brandId, String keyword, final int limit, final int offset,
                                                                 boolean includeArchived, IUser user) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findForBrandBySimilarity(brandId, keyword, limit, offset, includeArchived, user);
    }

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
        assert queryBuilder != null;
        String sql = queryBuilder.buildGetAllQuery(entityData.getTableName(), entityData.getRlsName(),
                user, includeArchived, filter, limit, offset);
        
        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm()))
                    .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                    .onItem().transformToUni(row -> from(row, false, false, false))
                    .concatenate()
                    .collect().asList();
        }
        
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, SoundFragmentFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            assert queryBuilder != null;
            sql += queryBuilder.buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm()))
                    .onItem().transform(rows -> rows.iterator().next().getInteger(0));
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<List<SoundFragment>> findByTypeAndBrand(PlaylistItemType type, UUID brandId, int limit, int offset) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.getBrandSongs(brandId, type, limit, offset);
    }

    public Uni<List<SoundFragment>> search(String searchTerm, final int limit, final int offset,
                                           final boolean includeArchived, final IUser user,
                                           final SoundFragmentFilter filter) {
        assert queryBuilder != null;
        String sql = queryBuilder.buildSearchQuery(entityData.getTableName(), entityData.getRlsName(),
                searchTerm, includeArchived, filter, limit, offset, true);

        List<Object> params = new ArrayList<>();
        params.add(user.getId());

        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            params.add(searchTerm.trim());
        }

        return client.preparedQuery(sql)
                .execute(Tuple.from(params))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<FileMetadata> getFirstFile(UUID id) {
        assert fileHandler != null;
        return fileHandler.getFirstFile(id);
    }

    public Uni<FileMetadata> getFileBySlugName(UUID id, String slugName, IUser user, boolean includeArchived) {
        assert fileHandler != null;
        return fileHandler.getFileBySlugName(id, slugName)
                .onFailure().recoverWithUni(ex -> {
                    markAsCorrupted(id).subscribe().with(
                            result -> LOGGER.info("Marked file {} as corrupted", id),
                            failure -> LOGGER.error("Failed to mark file {} as corrupted", id, failure)
                    );
                    return Uni.createFrom().failure(ex);
                });
    }

    public Uni<SoundFragment> findById(UUID uuid, Long userID, boolean includeArchived, boolean includeGenres, boolean includeFiles) {
        String sql = "SELECT theTable.*, rls.*" +
                String.format(" FROM %s theTable JOIN %s rls ON theTable.id = rls.entity_id ", entityData.getTableName(), entityData.getRlsName()) +
                "WHERE rls.reader = $1 AND theTable.id = $2";
        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, includeGenres, includeFiles, true);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }

    public Uni<List<SoundFragment>> getBrandSongsRandomPage(UUID brandId, PlaylistItemType type) {
        int limit = 200;
        int offset = secureRandom.nextInt(20) * limit;
        SoundFragmentBrandRepository brandRepository =
                new SoundFragmentBrandRepository(client, mapper, rlsRepository);

        return brandRepository.getBrandSongs(brandId, type, limit, offset);
    }

    public Uni<List<SoundFragment>> findByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        String placeholders = ids.stream()
                .map(id -> "'" + id.toString() + "'")
                .collect(java.util.stream.Collectors.joining(","));
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t " +
                "WHERE t.id IN (" + placeholders + ") AND t.archived = 0";
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<List<SoundFragment>> findByFilter(UUID brandId, SoundFragmentFilter filter, int limit) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findByFilter(brandId, filter, limit);
    }

    public Uni<SoundFragment> insert(SoundFragment doc, List<UUID> representedInBrands, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();
        final List<FileMetadata> originalFiles = doc.getFileMetadataList();

        final List<FileMetadata> filesToProcess = (originalFiles != null && !originalFiles.isEmpty())
                ? List.of(originalFiles.getFirst())
                : null;

        if (filesToProcess != null) {
            FileMetadata meta = filesToProcess.getFirst();
            Path filePath = meta.getFilePath();
            if (filePath == null) {
                throw new IllegalArgumentException("File metadata contains an entry with a null file path.");
            }
            if (!Files.exists(filePath)) {
                throw new UploadAbsenceException("Upload file not found at path: " + filePath);
            }
            meta.setFileOriginalName(filePath.getFileName().toString());
            meta.setSlugName(WebHelper.generateSlug(doc.getArtist(), doc.getTitle()));
            String doKey = WebHelper.generateSlugPath("music", doc.getArtist(), String.valueOf(UUID.randomUUID()));
            meta.setFileKey(doKey);
            meta.setMimeType(detectMimeType(filePath.toString()));
            doc.setFileMetadataList(filesToProcess);
        }

        return executeInsertTransaction(doc, user, nowTime, Uni.createFrom().voidItem(), representedInBrands)
                .onItem().transformToUni(insertedDoc -> {
                    if (filesToProcess != null && !filesToProcess.isEmpty()) {
                        FileMetadata meta = filesToProcess.get(0);
                        assert fileStorage != null;
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

    public Uni<Integer> archive(UUID id, IUser user) {
        return archive(id, entityData, user);
    }

    public Uni<Integer> delete(UUID uuid, IUser user) {
        return findById(uuid, user.getId(), true, false, false)
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
                                        .map(key -> {
                                                    assert fileStorage != null;
                                                    return fileStorage.deleteFile(key)
                                                            .onFailure().recoverWithUni(e -> {
                                                                LOGGER.error("Failed to delete file {} from storage for SoundFragment {}. DB record deletion will proceed.", key, uuid, e);
                                                                return Uni.createFrom().voidItem();
                                                            });
                                                }
                                        ).collect(Collectors.toList());

                                return Uni.combine().all().unis(deleteFileUnis).discardItems();
                            }).onItem().transformToUni(v -> {
                                return client.withTransaction(tx -> {
                                    String deleteGenresSql = "DELETE FROM mixpla__sound_fragment_genres WHERE sound_fragment_id = $1";
                                    String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
                                    String deleteFilesSql = "DELETE FROM _files WHERE parent_id = $1";
                                    String deleteDocSql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

                                    Uni<RowSet<Row>> genresDelete = tx.preparedQuery(deleteGenresSql).execute(Tuple.of(uuid));
                                    Uni<RowSet<Row>> rlsDelete = tx.preparedQuery(deleteRlsSql).execute(Tuple.of(uuid));
                                    Uni<RowSet<Row>> filesDelete = tx.preparedQuery(deleteFilesSql).execute(Tuple.of(uuid));

                                    return Uni.combine().all().unis(genresDelete, rlsDelete, filesDelete)
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
                            "title, artist, album, length, description, slug_name) " +
                            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13) RETURNING id;",
                    entityData.getTableName()
            );

            Long lengthMillis = doc.getLength() != null ? doc.getLength().toMillis() : null;
            
            Tuple params = Tuple.of(regDate, user.getId(), regDate, user.getId())
                    .addString(doc.getSource().name())
                    .addInteger(doc.getStatus())
                    .addString(doc.getType().name())
                    .addString(doc.getTitle())
                    .addString(doc.getArtist())
                    .addString(doc.getAlbum())
                    .addLong(lengthMillis)
                    .addString(doc.getDescription())
                    .addString(doc.getSlugName());

            return client.withTransaction(tx -> tx.preparedQuery(sql)
                    .execute(params)
                    .onItem().transform(result -> result.iterator().next().getUUID("id"))
                    .onItem().transformToUni(id -> {
                        Uni<Void> fileMetadataUni = insertFileMetadata(tx, id, doc);
                        return fileMetadataUni
                                .onItem().transformToUni(ignored -> insertGenreAssociations(tx, id, doc.getGenres()))
                                .onItem().transformToUni(ignored -> upsertLabels(tx, id, doc.getLabels()))
                                .onItem().transformToUni(ignored -> insertRLSPermissions(tx, id, entityData, user))
                                .onItem().transformToUni(ignored -> {
                                    assert brandHandler != null;
                                    return brandHandler.insertBrandAssociations(tx, id, representedInBrands, user);
                                })
                                .onItem().transform(ignored -> id);
                    })
            );
        }).onItem().transformToUni(id -> findById(id, user.getId(), true, true, true));
    }

    private Uni<Void> insertGenreAssociations(SqlClient tx, UUID soundFragmentId, List<UUID> genreIds) {
        if (genreIds == null || genreIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String insertSql = "INSERT INTO mixpla__sound_fragment_genres (sound_fragment_id, genre_id) VALUES ($1, $2)";
        List<Tuple> params = genreIds.stream()
                .map(id -> Tuple.of(soundFragmentId, id))
                .collect(Collectors.toList());

        return tx.preparedQuery(insertSql)
                .executeBatch(params)
                .onItem().ignore().andContinueWithNull();
    }

    private Uni<Void> updateGenreAssociations(SqlClient tx, UUID soundFragmentId, List<UUID> genreIds) {
        String deleteSql = "DELETE FROM mixpla__sound_fragment_genres WHERE sound_fragment_id = $1";
        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToUni(ignored -> insertGenreAssociations(tx, soundFragmentId, genreIds));
    }

    private Uni<Void> insertFileMetadata(SqlClient tx, UUID id, SoundFragment doc) {
        if (doc.getFileMetadataList() == null || doc.getFileMetadataList().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String filesSql = "INSERT INTO _files (parent_table, parent_id, storage_type, " +
                "mime_type, file_original_name, file_key, file_bin, slug_name) " +
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

    private Uni<Void> upsertLabels(SqlClient tx, UUID fragmentId, List<UUID> labels) {
        if (labels == null || labels.isEmpty()) {
            return tx.preparedQuery("DELETE FROM kneobroadcaster__sound_fragment_labels WHERE id = $1")
                    .execute(Tuple.of(fragmentId))
                    .replaceWithVoid();
        }

        String deleteSql = "DELETE FROM kneobroadcaster__sound_fragment_labels WHERE id = $1";
        String insertSql = "INSERT INTO kneobroadcaster__sound_fragment_labels (id, label_id) VALUES ($1, $2) ON CONFLICT DO NOTHING";

        return tx.preparedQuery(deleteSql)
                .execute(Tuple.of(fragmentId))
                .chain(() -> Multi.createFrom().iterable(labels)
                        .onItem().transformToUni(labelId ->
                                tx.preparedQuery(insertSql).execute(Tuple.of(fragmentId, labelId))
                        )
                        .merge()
                        .collect().asList()
                        .replaceWithVoid());
    }



    //TODO will be refactored later (fabric)
    public Uni<List<BrandSoundFragment>> getForBrand(UUID brandId, final int limit, final int offset,
                                                     boolean includeArchived, IUser user, SoundFragmentFilter filter) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findForBrand(brandId, limit, offset, includeArchived, user, filter);
    }

    public Uni<Integer> getForBrandCount(UUID brandId, IUser user, SoundFragmentFilter filter) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findForBrandCount(brandId, user, filter);
    }

    public Uni<List<SoundFragment>> getBrandSongs(UUID brandId, PlaylistItemType fragmentType) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.getBrandSongs(brandId, fragmentType, 200, 0);
    }

    public Uni<Integer> copyBrandSoundFragments(UUID sourceBrandId, UUID targetBrandId) {
        String sql = "INSERT INTO kneobroadcaster__brand_sound_fragments (brand_id, sound_fragment_id, played_by_brand_count, last_time_played_by_brand) " +
                "SELECT $2, src.sound_fragment_id, 0, NULL " +
                "FROM kneobroadcaster__brand_sound_fragments src " +
                "WHERE src.brand_id = $1 " +
                "AND NOT EXISTS (" +
                "  SELECT 1 FROM kneobroadcaster__brand_sound_fragments tgt " +
                "  WHERE tgt.brand_id = $2 AND tgt.sound_fragment_id = src.sound_fragment_id" +
                ")";

        return client.preparedQuery(sql)
                .execute(Tuple.of(sourceBrandId, targetBrandId))
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<Integer> updateRatedByBrandCount(UUID brandId, UUID soundFragmentId, int delta, IUser user) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.updateRatedByBrandCount(brandId, soundFragmentId, delta, user);
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

    public Uni<SoundFragment> update(UUID id, SoundFragment doc, List<UUID> representedInBrands, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new DocumentModificationAccessException("User does not have edit permission", user.getUserName(), id));
                    }

                    return findById(id, user.getId(), true, true, true)
                            .onItem().transformToUni(existingDoc -> {
                                final List<FileMetadata> originalFiles = doc.getFileMetadataList();
                                final List<FileMetadata> newFiles = (originalFiles != null && !originalFiles.isEmpty())
                                        ? List.of(originalFiles.get(0))
                                        : null;

                                Uni<Void> fileStoredUni = handleFileUpdate(id, doc, newFiles);

                                return fileStoredUni.onItem().transformToUni(ignored -> {
                                    LocalDateTime nowTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

                                    return client.withTransaction(tx -> {
                                        Uni<Void> chain = Uni.createFrom().voidItem();
                                        if (newFiles != null) {
                                            chain = deleteExistingFiles(tx, id)
                                                    .onItem().transformToUni(v -> insertNewFiles(tx, id, newFiles));
                                        }
                                        return chain
                                                .onItem().transformToUni(v -> updateGenreAssociations(tx, id, doc.getGenres()))
                                                .onItem().transformToUni(v -> upsertLabels(tx, id, doc.getLabels()))
                                                .onItem().transformToUni(v -> {
                                                    assert brandHandler != null;
                                                    return brandHandler.updateBrandAssociations(tx, id, representedInBrands, user);
                                                })
                                                .onItem().transformToUni(v -> updateSoundFragmentRecord(tx, id, doc, user, nowTime));
                                    }).onItem().transformToUni(rowSet -> {
                                        if (rowSet.rowCount() == 0) {
                                            return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                                        }
                                        return findById(id, user.getId(), true, true, true);
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

        String doKey = WebHelper.generateSlugPath("music", doc.getArtist(), String.valueOf(UUID.randomUUID()));
        meta.setFileKey(doKey);
        meta.setMimeType(detectMimeType(localPath));
        meta.setFileOriginalName(path.getFileName().toString());
        meta.setSlugName(WebHelper.generateSlug(doc.getArtist(), doc.getTitle()));

        LOGGER.debug("Storing file - Key: {}, Path: {}, Artist: {}, Title: {}", doKey, localPath, doc.getArtist(), doc.getTitle());

        assert fileStorage != null;
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
                        "status=$3, type=$4, title=$5, " +
                        "artist=$6, album=$7, length=$8, description=$9, slug_name=$10 WHERE id=$11;",
                entityData.getTableName());

        Tuple params = Tuple.of(user.getId(), nowTime)
                .addInteger(doc.getStatus())
                .addString(doc.getType().name())
                .addString(doc.getTitle())
                .addString(doc.getArtist())
                .addString(doc.getAlbum())
                .addLong(doc.getLength() != null ? doc.getLength().toMillis() : null)
                .addString(doc.getDescription())
                .addString(doc.getSlugName())
                .addUUID(id);

        return tx.preparedQuery(updateSql).execute(params);
    }

    public Uni<List<DocumentAccessInfo>> getDocumentAccessInfo(UUID documentId, IUser user) {
        return getDocumentAccessInfo(documentId, entityData, user);
    }
}