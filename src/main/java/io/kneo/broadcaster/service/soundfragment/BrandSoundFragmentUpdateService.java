package io.kneo.broadcaster.service.soundfragment;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class BrandSoundFragmentUpdateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandSoundFragmentUpdateService.class);

    @Inject
    PgPool client;

    public Uni<Void> updatePlayedCountAsync(UUID brandId, UUID soundFragmentId) {
        String sql = "WITH sf AS (SELECT id FROM kneobroadcaster__sound_fragments WHERE id = $2) " +
                "INSERT INTO kneobroadcaster__brand_sound_fragments " +
                "(brand_id, sound_fragment_id, played_by_brand_count, last_time_played_by_brand) " +
                "SELECT $1, sf.id, 1, NOW() FROM sf " +
                "ON CONFLICT (brand_id, sound_fragment_id) DO UPDATE SET " +
                "played_by_brand_count = kneobroadcaster__brand_sound_fragments.played_by_brand_count + 1, " +
                "last_time_played_by_brand = NOW()";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, soundFragmentId))
                .onItem().invoke(result -> {
                    int affected = result.rowCount();
                    if (affected == 0) {
                        LOGGER.warn("Skipped played count update: sound fragment not found in kneobroadcaster__sound_fragments. fragmentId={}, brandId={}",
                                soundFragmentId, brandId);
                    } else {
                        LOGGER.info("Played count upserted - affected rows: {}, fragment: {}", affected, soundFragmentId);
                    }
                })
                .onFailure().invoke(error -> LOGGER.error("Failed to update played count for fragment {}: {}",
                        soundFragmentId, error.getMessage(), error))
                .replaceWithVoid();
    }

    public Uni<Void> updateRatedCount(UUID brandId, UUID soundFragmentId, int delta) {
        String sql = "WITH sf AS (SELECT id FROM kneobroadcaster__sound_fragments WHERE id = $2) " +
                "INSERT INTO kneobroadcaster__brand_sound_fragments " +
                "(brand_id, sound_fragment_id, rated_by_brand_count) " +
                "SELECT $1, sf.id, $3 FROM sf " +
                "ON CONFLICT (brand_id, sound_fragment_id) DO UPDATE SET " +
                "rated_by_brand_count = kneobroadcaster__brand_sound_fragments.rated_by_brand_count + $3";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, soundFragmentId, delta))
                .onItem().invoke(result -> {
                    int affected = result.rowCount();
                    if (affected == 0) {
                        LOGGER.warn("Skipped rated count update: sound fragment not found. fragmentId={}, brandId={}",
                                soundFragmentId, brandId);
                    } else {
                        LOGGER.info("Rated count updated by {} - affected rows: {}, fragment: {}", delta, affected, soundFragmentId);
                    }
                })
                .onFailure().invoke(error -> LOGGER.error("Failed to update rated count for fragment {}: {}",
                        soundFragmentId, error.getMessage(), error))
                .replaceWithVoid();
    }
}
