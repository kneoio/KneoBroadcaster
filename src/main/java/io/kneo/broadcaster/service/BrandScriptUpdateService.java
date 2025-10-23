package io.kneo.broadcaster.service;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class BrandScriptUpdateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandScriptUpdateService.class);

    @Inject
    PgPool client;

    public Uni<Void> addScriptToBrand(UUID brandId, UUID scriptId, int rank, boolean active) {
        String sql = "INSERT INTO kneobroadcaster__brand_scripts " +
                "(brand_id, script_id, rank, active) " +
                "VALUES ($1, $2, $3, $4) " +
                "ON CONFLICT (brand_id, script_id) " +
                "DO UPDATE SET rank = $3, active = $4";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, scriptId, rank, active))
                .onItem().invoke(result -> {
                    LOGGER.info("Script {} added/updated for brand {} - rank: {}, active: {}",
                            scriptId, brandId, rank, active);
                })
                .onFailure().invoke(error -> LOGGER.error("Failed to add script {} to brand {}: {}",
                        scriptId, brandId, error.getMessage(), error))
                .replaceWithVoid();
    }

    public Uni<Void> removeScriptFromBrand(UUID brandId, UUID scriptId) {
        String sql = "DELETE FROM kneobroadcaster__brand_scripts " +
                "WHERE brand_id = $1 AND script_id = $2";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, scriptId))
                .onItem().invoke(result -> {
                    LOGGER.info("Script {} removed from brand {} - affected rows: {}",
                            scriptId, brandId, result.rowCount());
                })
                .onFailure().invoke(error -> LOGGER.error("Failed to remove script {} from brand {}: {}",
                        scriptId, brandId, error.getMessage(), error))
                .replaceWithVoid();
    }

    public Uni<Void> updateScriptRank(UUID brandId, UUID scriptId, int rank) {
        String sql = "UPDATE kneobroadcaster__brand_scripts " +
                "SET rank = $3 " +
                "WHERE brand_id = $1 AND script_id = $2";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, scriptId, rank))
                .onItem().invoke(result -> {
                    LOGGER.info("Script {} rank updated for brand {} - new rank: {}",
                            scriptId, brandId, rank);
                })
                .onFailure().invoke(error -> LOGGER.error("Failed to update rank for script {} in brand {}: {}",
                        scriptId, brandId, error.getMessage(), error))
                .replaceWithVoid();
    }

    public Uni<Void> toggleScriptActive(UUID brandId, UUID scriptId, boolean active) {
        String sql = "UPDATE kneobroadcaster__brand_scripts " +
                "SET active = $3 " +
                "WHERE brand_id = $1 AND script_id = $2";

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, scriptId, active))
                .onItem().invoke(result -> {
                    LOGGER.info("Script {} active status updated for brand {} - active: {}",
                            scriptId, brandId, active);
                })
                .onFailure().invoke(error -> LOGGER.error("Failed to update active status for script {} in brand {}: {}",
                        scriptId, brandId, error.getMessage(), error))
                .replaceWithVoid();
    }
}
