package io.kneo.broadcaster.repository.file;

import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.cnst.AccessType;
import io.kneo.broadcaster.model.cnst.FileStorageType;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.UUID;

@ApplicationScoped
@Named("database")
public class DatabaseStorage implements IFileStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseStorage.class);

    private final PgPool client;

    @Inject
    public DatabaseStorage(PgPool client) {
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
        String updateSql = "UPDATE _files SET file_bin = $2, mime_type = $3, parent_table = $4, parent_id = $5, last_mod_date = NOW() WHERE file_key = $1";
        String insertSql = "INSERT INTO _files (file_key, file_bin, mime_type, parent_table, parent_id, archived, storage_type) " +
                "VALUES ($1, $2, $3, $4, $5, 0, '" + FileStorageType.DATABASE + "')";

        return client.preparedQuery(updateSql)
                .execute(Tuple.of(key, fileContent, mimeType, tableName, id))
                .onItem().transformToUni(updateResult -> {
                    if (updateResult.rowCount() > 0) {
                        LOGGER.debug("Successfully updated existing file with key: {}", key);
                        return Uni.createFrom().item(key);
                    } else {
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
    public Uni<FileMetadata> retrieveFile(String key) {
        String sql = "SELECT id, reg_date, last_mod_date, parent_table, parent_id, archived, archived_date, " +
                "storage_type, mime_type, file_original_name, file_key, file_bin FROM _files WHERE file_key = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(key))
                .onItem().transform(rowSet -> {
                    if (rowSet.rowCount() == 0) {
                        throw new RuntimeException("File not found with key: " + key);
                    }
                    Row row = rowSet.iterator().next();
                    FileMetadata metadata = new FileMetadata();
                    metadata.setAccessType(AccessType.AS_DB_FIELD);
                    metadata.setId(row.getLong("id"));
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
                    metadata.setFileBin(row.getBuffer("file_bin").getBytes());
                    return metadata;
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
    public FileStorageType getStorageType() {
        return FileStorageType.DATABASE;
    }
}