package io.kneo.broadcaster.repository.soundfragment;

import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.repository.file.IFileStorage;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.util.UUID;
import java.util.function.Supplier;

@ApplicationScoped
public class SoundFragmentFileHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentFileHandler.class);

    private final PgPool client;
    private final IFileStorage fileStorage;
    private final Supplier<Uni<Integer>> corruptionMarker;

    @Inject
    public SoundFragmentFileHandler(PgPool client, @Named("digitalOcean") IFileStorage fileStorage) {
        this.client = client;
        this.fileStorage = fileStorage;
        this.corruptionMarker = null; // Will be set by repository
    }

    public Uni<FileMetadata> getFileById(UUID id) {
        String sql = "SELECT f.file_key FROM _files f WHERE f.parent_id = $1";
        return retrieveFileFromStorage(id, sql, Tuple.of(id));
    }

    public Uni<FileMetadata> getFileById(UUID id, String slugName) {
        String sql = "SELECT f.file_key FROM _files f WHERE f.parent_id = $1 AND f.slug_name = $2";
        return retrieveFileFromStorage(id, sql, Tuple.of(id, slugName));
    }

    private Uni<FileMetadata> retrieveFileFromStorage(UUID id, String sql, Tuple parameters) {
        return client.preparedQuery(sql)
                .execute(parameters)
                .onFailure().invoke(failure -> LOGGER.error("Database query failed for ID: {}", id, failure))
                .onItem().transformToUni(rows -> {
                    if (rows.rowCount() == 0) {
                        return handleMissingFileRecord(id);
                    }

                    String doKey = rows.iterator().next().getString("file_key");
                    LOGGER.debug("Retrieving file with key: {} for ID: {}", doKey, id);

                    return fileStorage.retrieveFile(doKey)
                            .onItem().invoke(file -> LOGGER.debug("File retrieval successful for ID: {}", id))
                            .onFailure().recoverWithUni(ex -> handleFileRetrievalFailure(id, doKey, ex));
                });
    }

    private Uni<FileMetadata> handleMissingFileRecord(UUID id) {
        LOGGER.warn("No file record found for ID: {}", id);
        // Mark as corrupted through repository callback
        return Uni.createFrom().failure(new DocumentHasNotFoundException("File not found: " + id));
    }

    private Uni<FileMetadata> handleFileRetrievalFailure(UUID id, String doKey, Throwable ex) {
        LOGGER.error("File retrieval failed - ID: {}, Key: {}, Error: {}", id, doKey, ex.getMessage());

        String errorMsg = String.format("File retrieval failed - ID: %s, Key: %s, Error: %s",
                id, doKey, ex.getClass().getSimpleName());
        FileNotFoundException fnf = new FileNotFoundException(errorMsg);
        fnf.initCause(ex);
        return Uni.createFrom().<FileMetadata>failure(fnf);
    }
}
