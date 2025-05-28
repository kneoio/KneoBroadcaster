package io.kneo.broadcaster.repository.file;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

@ApplicationScoped
@Default
public class DatabaseStorageStrategy implements IFileStorageStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseStorageStrategy.class);
    private static final String STORAGE_TYPE = "DATABASE";

    private final PgPool client;

    @Inject
    public DatabaseStorageStrategy(PgPool client) {
        this.client = client;
    }

    @Override
    public Uni<String> storeFile(String key, String filePath, String mimeType, String tableName, UUID id) {
        try {
            byte[] fileContent = Files.readAllBytes(Paths.get(filePath));
            return storeFile(key, fileContent, mimeType, tableName, id);
        } catch (IOException e) {
            return Uni.createFrom().failure(new RuntimeException("Failed to read file from path: " + filePath, e));
        }
    }

    @Override
    public Uni<String> storeFile(String key, byte[] fileContent, String mimeType, String tableName, UUID id) {
        // Since file_key is not a primary key, we need to handle upserts differently
        // First try to update, if no rows affected, then insert
        String updateSql = "UPDATE _files SET file_bin = $2, mime_type = $3, parent_table = $4, parent_id = $5, last_mod_date = NOW() WHERE file_key = $1";
        String insertSql = "INSERT INTO _files (file_key, file_bin, mime_type, parent_table, parent_id, archived, storage_type) VALUES ($1, $2, $3, $4, $5, 0, 'DATABASE')";

        return client.preparedQuery(updateSql)
                .execute(Tuple.of(key, fileContent, mimeType, tableName, id))
                .onItem().transformToUni(updateResult -> {
                    if (updateResult.rowCount() > 0) {
                        LOGGER.debug("Successfully updated existing file with key: {}", key);
                        return Uni.createFrom().item(key);
                    } else {
                        // No rows updated, try insert
                        return client.preparedQuery(insertSql)
                                .execute(Tuple.of(key, fileContent, mimeType, tableName, id))
                                .onItem().transform(insertResult -> {
                                    LOGGER.debug("Successfully inserted new file with key: {}", key);
                                    return key;
                                });
                    }
                })
                .onFailure().recoverWithUni(ex -> {
                    LOGGER.error("Failed to store file with key: {}", key, ex);
                    return Uni.createFrom().failure(new RuntimeException("Failed to store file in database", ex));
                });
    }

    @Override
    public Uni<byte[]> retrieveFile(String key) {
        String sql = "SELECT file_bin FROM _files WHERE file_key = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(key))
                .onItem().transform(rowSet -> {
                    if (rowSet.rowCount() == 0) {
                        throw new RuntimeException("File not found with key: " + key);
                    }
                    return rowSet.iterator().next().getBuffer("file_bin").getBytes();
                })
                .onFailure().invoke(ex -> LOGGER.error("Failed to retrieve file with key: {}", key, ex))
                .onFailure().transform(ex -> new RuntimeException("Failed to retrieve file from database", ex));
    }

    @Override
    public Uni<Void> deleteFile(String key) {
        String sql = "DELETE FROM _files WHERE file_key = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(key))
                .onItem().transformToUni(rowSet -> {
                    if (rowSet.rowCount() == 0) {
                        LOGGER.warn("No file found to delete with key: {}", key);
                    } else {
                        LOGGER.debug("Successfully deleted file with key: {}", key);
                    }
                    return Uni.createFrom().voidItem();
                })
                .onFailure().invoke(ex -> LOGGER.error("Failed to delete file with key: {}", key, ex))
                .onFailure().transform(ex -> new RuntimeException("Failed to delete file from database", ex));
    }

    @Override
    public String getStorageType() {
        return STORAGE_TYPE;
    }
}