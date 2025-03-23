package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.PoolStats;
import io.kneo.broadcaster.dto.dashboard.StationStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.PlaylistStats;
import io.kneo.broadcaster.service.radio.PlaylistManager;
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

    //@Inject
    //PlaylistManager playlistManager;

    public Uni<PoolStats> getPoolInfo() {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        PoolStats poolStats = new PoolStats();
        poolStats.setTotalStations(100000);
        poolStats.setMinimumSegments(config.getMinSegments());
        poolStats.setSlidingWindowSize(config.getMaxSegments());
        poolStats.setOnlineStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE)
                .count());
        poolStats.setWarmingStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.WARMING_UP)
                .count());
        Map<String, StationStats> stationStats = pool.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> createStationStats(entry.getKey(), entry.getValue())
                ));
        poolStats.setStations(stationStats);
     //   stats.addPeriodicTask(playlistManager.getTaskTimeline());

        return Uni.createFrom().item(poolStats);
    }

    private StationStats createStationStats(String brand, RadioStation station) {
        StationStats stationStats = new StationStats();
        stationStats.setBrandName(brand);
        stationStats.setStatus(station.getStatus());

        if (station.getPlaylist() != null) {
            HLSPlaylist playlist = station.getPlaylist();
            PlaylistManager manager = playlist.getPlaylistManager();
            stationStats.addPeriodicTask(manager.getTaskTimeline());
            PlaylistManagerStats playlistManagerStats = manager.getStats();
            stationStats.setPlaylistManagerStats(playlistManagerStats);
            PlaylistStats playlistStats = playlist.getStats();
            stationStats.setSegmentsSize(playlistStats.getSegmentCount());
            stationStats.setLastSegmentKey(playlist.getLastSegmentKey());
            stationStats.setLastRequested(playlistStats.getLastRequestedSegment());
            stationStats.setLastSegmentTimestamp(playlistStats.getLastRequestedTimestamp());
            stationStats.setTotalBytesProcessed(playlistStats.getTotalBytesProcessed());
            stationStats.setBitrate(playlistStats.getBitrate());
            stationStats.setQueueSize(playlistStats.getQueueSize());
            stationStats.setLastUpdated(playlistStats.getNowTimestamp());
        } else {
            stationStats.setSegmentsSize(0);
        }

        return stationStats;
    }
}