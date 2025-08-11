package io.kneo.broadcaster.repository.file;

import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.cnst.AccessType;
import io.kneo.broadcaster.model.cnst.FileStorageType;
import io.kneo.broadcaster.service.FileOperationLockService;
import io.kneo.broadcaster.service.TransactionCoordinatorService;
import io.kneo.broadcaster.service.external.digitalocean.DigitalOceanSpacesService;
import io.kneo.broadcaster.service.filemaintainance.LocalFileCleanupService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.file.FileSystem;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Named("digitalOcean")
public class DigitalOceanStorage implements IFileStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(DigitalOceanStorage.class);

    private final DigitalOceanSpacesService digitalOceanSpacesService;
    private final LocalFileCleanupService localFileCleanupService;
    private final TransactionCoordinatorService transactionCoordinator;
    private final FileOperationLockService lockService;
    private final Vertx vertx;
    private final PgPool client;

    @Inject
    public DigitalOceanStorage(PgPool client, DigitalOceanSpacesService digitalOceanSpacesService, LocalFileCleanupService localFileCleanupService, TransactionCoordinatorService transactionCoordinator, FileOperationLockService lockService, Vertx vertx) {
        this.client = client;
        this.digitalOceanSpacesService = digitalOceanSpacesService;
        this.localFileCleanupService = localFileCleanupService;
        this.transactionCoordinator = transactionCoordinator;
        this.lockService = lockService;
        this.vertx = vertx;
    }


    public Uni<String> storeFileCoordinated(String key, String filePath, String mimeType,
                                            String tableName, UUID id, String username) {

        return lockService.withFileLock(username, id.toString(), Paths.get(filePath).getFileName().toString(), () -> {
            return transactionCoordinator.executeCoordinatedTransaction(context -> {

                // First, upload to DigitalOcean
                return digitalOceanSpacesService.uploadFile(key, filePath, mimeType)
                        .onItem().transformToUni(uploadResult -> {

                            // Register compensation action (delete from DigitalOcean if DB fails)
                            Uni<Void> compensation = digitalOceanSpacesService.deleteFile(key)
                                    .onFailure().invoke(ex ->
                                            LOGGER.error("Failed to compensate - delete file from DigitalOcean: {}", key, ex))
                                    .onFailure().recoverWithNull()
                                    .replaceWithVoid();

                            context.addCompensationAction(compensation);

                            // Now update/insert database record within the transaction
                            return storeFileMetadataInTransaction(context, key, mimeType, tableName, id);
                        })
                        .onItem().transformToUni(dbResult -> {
                            // Cleanup local file after successful storage
                            Path path = Paths.get(filePath);
                            String fileName = path.getFileName().toString();

                            Path pathObj = path.getParent();
                            if (pathObj != null) {
                                String entityId = pathObj.getFileName().toString();
                                Path userPath = pathObj.getParent();
                                if (userPath != null) {
                                    String extractedUsername = userPath.getFileName().toString();

                                    return localFileCleanupService.cleanupAfterSuccessfulUpload(
                                                    extractedUsername, entityId, fileName)
                                            .onItem().transform(ignored -> key)
                                            .onFailure().invoke(ex ->
                                                    LOGGER.warn("Failed to cleanup local file after upload: {}", filePath, ex));
                                }
                            }
                            return Uni.createFrom().item(key);
                        });
            });
        });
    }

    // Helper method to store file metadata within transaction
    private Uni<String> storeFileMetadataInTransaction(TransactionCoordinatorService.CoordinatedTransactionContext context,
                                                       String key, String mimeType,
                                                       String tableName, UUID id) {

        String updateSql = "UPDATE _files SET file_bin = NULL, mime_type = $2, parent_table = $3, parent_id = $4, last_mod_date = NOW() WHERE file_key = $1";
        String insertSql = "INSERT INTO _files (file_key, file_bin, mime_type, parent_table, parent_id, storage_type, archived)" +
                " VALUES ($1, NULL, $2, $3, $4, 'DIGITAL_OCEAN', 0)";

        return context.getSqlConnection().preparedQuery(updateSql)
                .execute(Tuple.of(key, mimeType, tableName, id))
                .onItem().transformToUni(updateResult -> {
                    if (updateResult.rowCount() > 0) {
                        LOGGER.debug("Updated existing file metadata with key: {}", key);
                        return Uni.createFrom().item(key);
                    } else {
                        return context.getSqlConnection().preparedQuery(insertSql)
                                .execute(Tuple.of(key, mimeType, tableName, id))
                                .onItem().transform(insertResult -> {
                                    LOGGER.debug("Inserted new file metadata with key: {}", key);
                                    return key;
                                });
                    }
                });
    }

    // Add batch storage method for multiple files
    public Uni<List<String>> storeMultipleFilesCoordinated(List<FileUploadRequest> requests, String username) {

        // Create lock keys for all files
        String[] lockKeys = requests.stream()
                .map(req -> "file:" + username + ":" + req.getEntityId() + ":" +
                        Paths.get(req.getFilePath()).getFileName().toString())
                .toArray(String[]::new);

        return lockService.withMultipleLocks(lockKeys, () -> {
            return transactionCoordinator.executeCoordinatedTransaction(context -> {

                List<TransactionCoordinatorService.FileOperation> fileOps = requests.stream()
                        .map(req -> new FileUploadOperation(req, context))
                        .collect(java.util.stream.Collectors.toList());

                return transactionCoordinator.executeAtomicFileOperations(context, fileOps)
                        .onItem().transform(ignored ->
                                requests.stream()
                                        .map(FileUploadRequest::getKey)
                                        .collect(java.util.stream.Collectors.toList()));
            });
        }, "batch file upload for user: " + username);
    }

    // File upload operation implementation
    private class FileUploadOperation implements TransactionCoordinatorService.FileOperation {
        private final FileUploadRequest request;
        private final TransactionCoordinatorService.CoordinatedTransactionContext context;

        public FileUploadOperation(FileUploadRequest request, TransactionCoordinatorService.CoordinatedTransactionContext context) {
            this.request = request;
            this.context = context;
        }

        @Override
        public Uni<Void> execute() {
            return digitalOceanSpacesService.uploadFile(request.getKey(), request.getFilePath(), request.getMimeType())
                    .onItem().transformToUni(uploadResult ->
                            storeFileMetadataInTransaction(context, request.getKey(), request.getMimeType(),
                                    request.getTableName(), request.getEntityId())
                                    .replaceWithVoid()
                    );
        }

        @Override
        public Uni<Void> getCompensation() {
            return digitalOceanSpacesService.deleteFile(request.getKey())
                    .onFailure().invoke(ex ->
                            LOGGER.error("Failed to compensate - delete file: {}", request.getKey(), ex))
                    .onFailure().recoverWithNull()
                    .replaceWithVoid();
        }
    }











    @Override
    public Uni<String> storeFile(String key, String filePath, String mimeType, String tableName, UUID id) {
        return digitalOceanSpacesService.uploadFile(key, filePath, mimeType)
                .onItem().transformToUni(v -> {
                    String updateSql = "UPDATE _files SET file_bin = NULL, mime_type = $2, parent_table = $3, parent_id = $4, last_mod_date = NOW() WHERE file_key = $1";
                    String insertSql = "INSERT INTO _files (file_key, file_bin, mime_type, parent_table, parent_id, storage_type, archived)" +
                            " VALUES ($1, NULL, $2, $3, $4, 'DIGITAL_OCEAN', 0)";

                    return client.preparedQuery(updateSql)
                            .execute(Tuple.of(key, mimeType, tableName, id))
                            .onItem().transformToUni(updateResult -> {
                                if (updateResult.rowCount() > 0) {
                                    return Uni.createFrom().item(key);
                                } else {
                                    return client.preparedQuery(insertSql)
                                            .execute(Tuple.of(key, mimeType, tableName, id))
                                            .onItem().transform(insertResult -> key);
                                }
                            })
                            .onItem().transformToUni(storedKey -> {
                                Path path = Paths.get(filePath);
                                String fileName = path.getFileName().toString();

                                Path pathObj = path.getParent();
                                if (pathObj != null) {
                                    String entityId = pathObj.getFileName().toString();
                                    Path userPath = pathObj.getParent();
                                    if (userPath != null) {
                                        String username = userPath.getFileName().toString();

                                        return localFileCleanupService.cleanupAfterSuccessfulUpload(username, entityId, fileName)
                                                .onItem().transform(ignored -> storedKey)
                                                .onFailure().invoke(ex ->
                                                        LOGGER.warn("Failed to cleanup local file after upload: {}", filePath, ex));
                                    }
                                }
                                return Uni.createFrom().item(storedKey);
                            });
                })
                .onFailure().transform(ex -> {
                    LOGGER.error("Failed to store file with key: {}", key, ex);
                    return new RuntimeException("Failed to store file", ex);
                });
    }

    @Override
    public Uni<String> storeFile(String key, byte[] fileContent, String mimeType, String tableName, UUID id) {
        try {
            Path tempFile = Files.createTempFile("upload_", ".tmp");
            Files.write(tempFile, fileContent, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            return digitalOceanSpacesService.uploadFile(key, tempFile.toString(), mimeType)
                    .onItem().transformToUni(v -> {
                        String updateSql = "UPDATE _files SET file_bin = NULL, mime_type = $2, parent_table = $3, parent_id = $4, " +
                                "storage_type = '" + FileStorageType.DIGITAL_OCEAN + "', last_mod_date = NOW() WHERE file_key = $1";
                        String insertSql = "INSERT INTO _files (file_key, file_bin, mime_type, parent_table, parent_id, storage_type, archived)" +
                                " VALUES ($1, NULL, $2, $3, $4, '" + FileStorageType.DIGITAL_OCEAN + "', 0)";

                        return client.preparedQuery(updateSql)
                                .execute(Tuple.of(key, mimeType, tableName, id))
                                .onItem().transformToUni(updateResult -> {
                                    if (updateResult.rowCount() > 0) {
                                        LOGGER.debug("Successfully updated existing file metadata with key: {}", key);
                                        return Uni.createFrom().item(key);
                                    } else {
                                        return client.preparedQuery(insertSql)
                                                .execute(Tuple.of(key, mimeType, tableName, id))
                                                .onItem().transform(insertResult -> {
                                                    LOGGER.debug("Successfully inserted new file metadata with key: {}", key);
                                                    return key;
                                                });
                                    }
                                });
                    })
                    .eventually(() -> {
                        FileSystem fs = vertx.fileSystem();
                        return fs.delete(tempFile.toString())
                                .onFailure().invoke(e -> LOGGER.warn("Failed to delete temporary file '{}': {}",
                                        tempFile, e.getMessage()));
                    })
                    .onFailure().transform(ex -> {
                        LOGGER.error("Failed to store file with key: {}", key, ex);
                        return new RuntimeException("Failed to store file", ex);
                    });
        } catch (IOException e) {
            return Uni.createFrom().failure(new RuntimeException("Failed to create temporary file", e));
        }
    }

    @Override
    public Uni<FileMetadata> retrieveFile(String key) {
        String metadataSql = "SELECT id, reg_date, last_mod_date, parent_table, parent_id, archived, archived_date, " +
                "storage_type, mime_type, file_original_name, file_key FROM _files WHERE file_key = $1";

        return client.preparedQuery(metadataSql)
                .execute(Tuple.of(key))
                .onItem().transformToUni(rowSet -> {
                    if (rowSet.rowCount() == 0) {
                        return Uni.createFrom().failure(new RuntimeException("File not found with key: " + key));
                    }

                    Row row = rowSet.iterator().next();
                    FileMetadata metadata = new FileMetadata();
                    metadata.setId(row.getLong("id"));
                    metadata.setAccessType(AccessType.ON_DISC);
                    metadata.setRegDate(row.getLocalDateTime("reg_date").atZone(ZoneId.systemDefault()));
                    metadata.setLastModifiedDate(row.getLocalDateTime("last_mod_date").atZone(ZoneId.systemDefault()));
                    metadata.setParentTable(row.getString("parent_table"));
                    metadata.setParentId(row.getUUID("parent_id"));
                    metadata.setArchived(row.getInteger("archived"));
                    if (row.getLocalDateTime("archived_date") != null) {
                        metadata.setArchivedDate(row.getLocalDateTime("archived_date"));
                    }
                    metadata.setFileStorageType(FileStorageType.valueOf(row.getString("storage_type")));
                    metadata.setMimeType(row.getString("mime_type"));
                    metadata.setFileOriginalName(row.getString("file_original_name"));
                    metadata.setFileKey(row.getString("file_key"));

                    return digitalOceanSpacesService.getFile(key)
                            .onItem().transformToUni(filePath -> {
                                if (filePath == null) {
                                    return Uni.createFrom().failure(new IOException("Failed to get file path for key: " + key));
                                }

                                FileSystem fs = vertx.fileSystem();
                                return fs.readFile(String.valueOf(filePath))
                                        .onItem().transform(buffer -> {
                                            //For get_slug we will keep also byte[]
                                            metadata.setFileBin(buffer.getBytes());
                                            metadata.setFilePath(filePath);
                                            return metadata;
                                        });
                            });
                })
                .onFailure().transform(ex -> {
                    if (ex instanceof PgException) {
                        LOGGER.error("PostgreSQL error while retrieving file with key: {}. Message: {}, SQL: {}",
                                key, ex.getMessage(), metadataSql);
                        return new RuntimeException("Database error while retrieving file", ex);
                    } else {
                        LOGGER.error("Failed to retrieve file with key: {}", key, ex);
                        return new RuntimeException("Failed to retrieve file from storage", ex);
                    }
                });
    }

    @Override
    public Uni<Void> deleteFile(String key) {
        return digitalOceanSpacesService.deleteFile(key)
                .onItem().transformToUni(v -> {
                    String deleteSql = "DELETE FROM _files WHERE file_key = $1";
                    return client.preparedQuery(deleteSql)
                            .execute(Tuple.of(key))
                            .onItem().transform(result -> {
                                LOGGER.debug("Successfully deleted file metadata with key: {}", key);
                                return null;
                            });
                })
                .onFailure().transform(ex -> {
                    LOGGER.error("Failed to delete file with key: {}", key, ex);
                    return new RuntimeException("Failed to delete file", ex);
                }).replaceWithVoid();
    }

    @Override
    public FileStorageType getStorageType() {
        return FileStorageType.DIGITAL_OCEAN;
    }

    @Getter
    public static class FileUploadRequest {
        // Getters
        private final String key;
        private final String filePath;
        private final String mimeType;
        private final String tableName;
        private final UUID entityId;

        public FileUploadRequest(String key, String filePath, String mimeType, String tableName, UUID entityId) {
            this.key = key;
            this.filePath = filePath;
            this.mimeType = mimeType;
            this.tableName = tableName;
            this.entityId = entityId;
        }

    }
}