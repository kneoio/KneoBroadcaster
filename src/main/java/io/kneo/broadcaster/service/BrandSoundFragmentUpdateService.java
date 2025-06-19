// 1. CREATE the BrandSoundFragmentUpdateService class
// You need to create this entire class in: io.kneo.broadcaster.service.BrandSoundFragmentUpdateService

package io.kneo.broadcaster.service;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.SqlResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class BrandSoundFragmentUpdateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandSoundFragmentUpdateService.class);
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    @Inject
    PgPool client;

    public void updatePlayedCountAsync(UUID brandId, UUID soundFragmentId, String brandName) {
        CompletableFuture.runAsync(() -> {
            try {
                updatePlayedCount(brandId, soundFragmentId, brandName)
                        .subscribe().with(
                                count -> LOGGER.debug("Successfully updated played count for fragment {} in brand {}",
                                        soundFragmentId, brandName),
                                error -> LOGGER.error("Failed to update played count for fragment {} in brand {}: {}",
                                        soundFragmentId, brandName, error.getMessage(), error)
                        );
            } catch (Exception e) {
                LOGGER.error("Unexpected error updating played count for fragment {} in brand {}: {}",
                        soundFragmentId, brandName, e.getMessage(), e);
            }
        }, executorService);
    }

    private Uni<Integer> updatePlayedCount(UUID brandId, UUID soundFragmentId, String brandName) {
        String sql = """
            INSERT INTO kneobroadcaster__brand_sound_fragments (brand_id, sound_fragment_id, played_by_brand_count, last_time_played_by_brand)
            VALUES ($1, $2, 1, $3)
            ON CONFLICT (brand_id, sound_fragment_id)
            DO UPDATE SET played_by_brand_count = kneobroadcaster__brand_sound_fragments.played_by_brand_count + 1,
                last_time_played_by_brand = $3
            """;

        return client
                .preparedQuery(sql)
                .execute(io.vertx.mutiny.sqlclient.Tuple.of(brandId, soundFragmentId, LocalDateTime.now()))
                .onItem().transform(SqlResult::rowCount)
                .onFailure().invoke(error ->
                        LOGGER.error("Database error updating played count for fragment {} in brand {}: {}",
                                soundFragmentId, brandName, error.getMessage(), error));
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
