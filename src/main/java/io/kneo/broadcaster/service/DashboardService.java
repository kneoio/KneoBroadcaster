package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.PoolStats;
import io.kneo.broadcaster.dto.dashboard.StationEntry;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class DashboardService {

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    public Uni<PoolStats> getPoolInfo() {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        PoolStats poolStats = new PoolStats();
        poolStats.setTotalStations(pool.size()); //TODO temporary
        poolStats.setMinimumSegments(config.getMinSegments());
        poolStats.setSlidingWindowSize(config.getMaxSegments());
        poolStats.setOnlineStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE)
                .count());
        poolStats.setWarmingStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.WARMING_UP)
                .count());
        List<StationEntry> stationStats = pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE || station.getStatus() == RadioStationStatus.WARMING_UP)
                .map(s -> new StationEntry(s.getSlugName()))
                .collect(Collectors.toList());
        poolStats.setStations(stationStats);
     //   stats.addPeriodicTask(playlistManager.getTaskTimeline());

        return Uni.createFrom().item(poolStats);
    }


}