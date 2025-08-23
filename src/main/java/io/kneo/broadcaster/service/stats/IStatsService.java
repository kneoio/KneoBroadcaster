package io.kneo.broadcaster.service.stats;

import io.smallrye.mutiny.Uni;

public interface IStatsService {

    Uni<Void> recordAccess(String stationName, String userAgent);

    Uni<StatsAccumulator.StationStats> getStationStats(String stationName);

    Uni<Void> flushStats();

    StatsServiceHealth getServiceHealth();

    record StatsServiceHealth(String implementation, boolean healthy, String details) {

    }
}
