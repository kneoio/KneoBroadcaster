package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.PoolStats;
import io.kneo.broadcaster.dto.dashboard.StationStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.stats.PlaylistStats;
import io.kneo.broadcaster.service.radio.SegmentsCleaner;
import io.kneo.broadcaster.service.radio.PlaylistKeeper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class DashboardService {

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    PlaylistKeeper playlistScheduler;

    @Inject
    SegmentsCleaner playlistCleanupService;

    public Uni<PoolStats> getPoolInfo() {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        PoolStats stats = new PoolStats();
        stats.setTotalStations(100000);
        stats.setMinimumSegments(config.getMinSegments());
        stats.setSlidingWindowSize(config.getSlidingWindowSize());
        stats.setOnlineStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE)
                .count());
        stats.setWarmingStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.WARMING_UP)
                .count());
        Map<String, StationStats> stationStats = pool.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> createStationStats(entry.getKey(), entry.getValue())
                ));
        stats.setStations(stationStats);
        stats.addPeriodicTask(playlistScheduler.getTaskTimeline());
        stats.addPeriodicTask(playlistCleanupService.getTaskTimeline());

        return Uni.createFrom().item(stats);
    }

    private StationStats createStationStats(String brand, RadioStation station) {
        StationStats stats = new StationStats();
        stats.setBrandName(brand);
        stats.setStatus(station.getStatus());

        if (station.getPlaylist() != null) {
            HLSPlaylist playlist = station.getPlaylist();
            PlaylistStats playlistStats = playlist.getStats();
            stats.setSegmentsSize(playlistStats.getSegmentCount());
            stats.setLastSegmentKey(playlist.getLastSegmentKey());
            stats.setLastRequested(playlistStats.getLastRequestedSegment());
            stats.setLastSegmentTimestamp(playlistStats.getLastRequestedTimestamp());
            stats.setCurrentFragment(playlistStats.getLastRequestedFragmentName());
            stats.setTotalBytesProcessed(playlistStats.getTotalBytesProcessed());
            stats.setBitrate(playlistStats.getBitrate());
            stats.setQueueSize(playlistStats.getQueueSize());
            stats.setRecentlyPlayedTitles(playlistStats.getRecentlyPlayedTitles());
            stats.setLastUpdated(playlistStats.getNowTimestamp());
        } else {
            stats.setSegmentsSize(0);
            stats.setRecentlyPlayedTitles(List.of());
        }

        return stats;
    }
}