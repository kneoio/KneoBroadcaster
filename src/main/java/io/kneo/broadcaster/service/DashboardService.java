package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.controller.stream.HLSSegmentStats;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.PoolStats;
import io.kneo.broadcaster.dto.dashboard.StationStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
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
        stationStats.setManagedBy(station.getManagedBy());

        if (station.getPlaylist() != null) {
            HLSPlaylist playlist = station.getPlaylist();
            PlaylistManager manager = playlist.getPlaylistManager();
            stationStats.addPeriodicTask(manager.getTaskTimeline());
            stationStats.setPlaylistManagerStats(manager.getStats());
            stationStats.setSegmentSizeHistory(playlist.getSegmentSizeHistory());
            HLSSegmentStats hlsSegmentStats = playlist.getStats();
            stationStats.setSongStatistics(hlsSegmentStats.getSongStatistics());
            stationStats.setSegmentsSize(hlsSegmentStats.getSegmentCount());
            stationStats.setLastRequested(hlsSegmentStats.getLastRequestedSegment());
            stationStats.setLastSegmentTimestamp(hlsSegmentStats.getLastRequestedTimestamp());
            stationStats.setTotalBytesProcessed(hlsSegmentStats.getTotalBytesProcessed());
            stationStats.setBitrate(hlsSegmentStats.getBitrate());
            stationStats.setQueueSize(hlsSegmentStats.getQueueSize());
            stationStats.setLastUpdated(hlsSegmentStats.getNowTimestamp());
        } else {
            stationStats.setSegmentsSize(0);
        }

        return stationStats;
    }
}