package io.kneo.broadcaster.service.stats;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


@ApplicationScoped
@DefaultBean
public class InMemoryStatsService implements IStatsService {

    @Inject
    StatsAccumulator statsAccumulator;

    @Override
    public Uni<Void> recordAccess(String stationName, String userAgent) {
        try {
            statsAccumulator.recordAccess(stationName, userAgent);
            return Uni.createFrom().voidItem();
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<StatsAccumulator.StationStats> getStationStats(String stationName) {
        try {
            StatsAccumulator.StationStats stats = statsAccumulator.getAccumulatedStats(stationName);
            return Uni.createFrom().item(stats);
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<Void> flushStats() {
        return statsAccumulator.flushAllStats();
    }

    @Override
    public StatsServiceHealth getServiceHealth() {
        try {
            int pendingStations = statsAccumulator.getPendingStatsCount();
            long totalPending = statsAccumulator.getTotalPendingAccesses();

            return new StatsServiceHealth(
                    "InMemory",
                    true,
                    String.format("Pending: %d stations, %d total accesses", pendingStations, totalPending)
            );
        } catch (Exception e) {
            return new StatsServiceHealth("InMemory", false, "Error: " + e.getMessage());
        }
    }
}



// Future Redis implementation
/*
@ApplicationScoped
@Alternative
@Priority(1)
public class RedisStatsService implements IStatsService {

    @Inject
    ReactiveRedisDataSource redis;

    @Override
    public Uni<Void> recordAccess(String stationName, String userAgent) {
        return redis.value(Long.class)
            .incr("station:access:" + stationName)
            .chain(count -> redis.value(String.class)
                .setex("station:agent:" + stationName, 86400, userAgent))
            .replaceWithVoid();
    }


}
*/

