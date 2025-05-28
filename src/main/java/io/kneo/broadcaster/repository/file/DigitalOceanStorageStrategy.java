package io.kneo.broadcaster.repository.file;

import io.kneo.broadcaster.service.external.DigitalOceanSpacesService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.file.FileSystem;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@ApplicationScoped
public class DigitalOceanStorageStrategy implements IFileStorageStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(DigitalOceanStorageStrategy.class);
    private static final String STORAGE_TYPE = "DIGITAL_OCEAN";

    private final DigitalOceanSpacesService digitalOceanSpacesService;
    private final Vertx vertx;

    private final PgPool client;


    @Inject
    public DigitalOceanStorageStrategy(PgPool client, DigitalOceanSpacesService digitalOceanSpacesService, Vertx vertx) {
        this.client = client;
        this.digitalOceanSpacesService = digitalOceanSpacesService;
        this.vertx = vertx;
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
                                    LOGGER.debug("Successfully updated existing file metadata with key: {}", key);
                                    return Uni.createFrom().item(key);
                                } else {
                                    // No rows updated, try insert
                                    return client.preparedQuery(insertSql)
                                            .execute(Tuple.of(key, mimeType, tableName, id))
                                            .onItem().transform(insertResult -> {
                                                LOGGER.debug("Successfully inserted new file metadata with key: {}", key);
                                                return key;
                                            });
                                }
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

            // First upload to Digital Ocean Spaces
            return digitalOceanSpacesService.uploadFile(key, tempFile.toString(), mimeType)
                    .onItem().transformToUni(v -> {
                        // After successful upload, save metadata to database
                        String updateSql = "UPDATE _files SET file_bin = NULL, mime_type = $2, parent_table = $3, parent_id = $4, storage_type = 'DIGITAL_OCEAN', last_mod_date = NOW() WHERE file_key = $1";
                        String insertSql = "INSERT INTO _files (file_key, file_bin, mime_type, parent_table, parent_id, storage_type, archived) VALUES ($1, NULL, $2, $3, $4, 'DIGITAL_OCEAN', 0)";

                        return client.preparedQuery(updateSql)
                                .execute(Tuple.of(key, mimeType, tableName, id))
                                .onItem().transformToUni(updateResult -> {
                                    if (updateResult.rowCount() > 0) {
                                        LOGGER.debug("Successfully updated existing file metadata with key: {}", key);
                                        return Uni.createFrom().item(key);
                                    } else {
                                        // No rows updated, try insert
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
                        // Clean up temporary file
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
    public Uni<byte[]> retrieveFile(String key) {
        return digitalOceanSpacesService.getFile(key)
                .onItem().transformToUni(filePath -> {
                    if (filePath == null) {
                        return Uni.createFrom().failure(new IOException("Failed to get file path for key: " + key));
                    }

                    FileSystem fs = vertx.fileSystem();
                    return fs.readFile(String.valueOf(filePath))
                            .onItem().transform(Buffer::getBytes)
                            .eventually(() -> fs.delete(String.valueOf(filePath))
                                    .onFailure().invoke(e -> LOGGER.warn("Failed to delete temporary file '{}': {}",
                                            filePath, e.getMessage())));
                })
                .onFailure().transform(ex -> {
                    LOGGER.error("Failed to retrieve file with key: {}", key, ex);
                    return new RuntimeException("Failed to retrieve file from Digital Ocean Spaces", ex);
                });
    }

    @Override
    public Uni<Void> deleteFile(String key) {
        // First delete from Digital Ocean Spaces
        return digitalOceanSpacesService.deleteFile(key)
                .onItem().transformToUni(v -> {
                    // After successful deletion from DO, remove metadata from database
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
    public String getStorageType() {
        return STORAGE_TYPE;
    }
}