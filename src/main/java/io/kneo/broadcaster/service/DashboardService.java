package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.PoolStats;
import io.kneo.broadcaster.dto.dashboard.StationStats;
import io.kneo.broadcaster.model.RadioStation;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class DashboardService {

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    public Uni<PoolStats> getPoolInfo(String brand) {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        PoolStats stats = new PoolStats();
        stats.setTotalStations(100000);
        stats.setMinimumSegments(config.getMinSegments());
        stats.setSlidingWindowSize(config.getSlidingWindowSize());
        stats.setOnlineStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE)
                .count());
        Map<String, StationStats> stationStats = pool.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> createStationStats(entry.getKey(), entry.getValue())
                ));
        stats.setStations(stationStats);
        return Uni.createFrom().item(stats);

    }

    private StationStats createStationStats(String brand, RadioStation station) {
        StationStats stats = new StationStats();
        stats.setBrandName(brand);
        stats.setStatus(station.getStatus());
        if (station.getPlaylist() != null) {
            HLSPlaylist playlist = station.getPlaylist();
            stats.setSegmentsSize(playlist.getSegmentCount());
            stats.setLastSegmentKey(playlist.getLastSegmentKey());
            stats.setLastRequested(playlist.getLastRequestedSegment());
            stats.setCurrentFragment(playlist.getLastRequestedFragmentName());
        } else {
            stats.setSegmentsSize(0);
        }

        return stats;
    }
}