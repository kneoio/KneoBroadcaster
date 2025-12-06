package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.rls.RLSRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class StorageUsageRepository  extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageUsageRepository.class);

    @Inject
    public StorageUsageRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }


    public Uni<Void> insertBatch(List<StorageUsageRecord> records) {
        String sql = "INSERT INTO _storage_usage (author, station_id, station_slug, total_bytes, file_count, " +
                "calculation_date, storage_type, last_mod_user) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";

        List<Tuple> params = records.stream()
                .map(r -> Tuple.tuple()
                        .addLong(r.author)
                        .addUUID(r.stationId)
                        .addString(r.stationSlug)
                        .addLong(r.totalBytes)
                        .addInteger(r.fileCount)
                        .addLocalDateTime(r.calculationDate)
                        .addString(r.storageType)
                        .addLong(r.author))
                .toList();

        return client.preparedQuery(sql)
                .executeBatch(params)
                .onItem().ignore().andContinueWithNull()
                .onFailure().invoke(t -> LOGGER.error("Failed to insert storage usage batch", t));
    }

    public Uni<Long> getTotalStorageByAuthor(Long author) {
        String sql = "SELECT SUM(total_bytes) FROM _storage_usage " +
                "WHERE author = $1 AND calculation_date = (SELECT MAX(calculation_date) FROM _storage_usage)";

        return client.preparedQuery(sql)
                .execute(Tuple.of(author))
                .onItem().transform(rows -> {
                    Long result = rows.iterator().next().getLong(0);
                    return result != null ? result : 0L;
                });
    }

    public record StorageUsageRecord(
            Long author,
            UUID stationId,
            String stationSlug,
            Long totalBytes,
            Integer fileCount,
            LocalDateTime calculationDate,
            String storageType
    ) {}
}
