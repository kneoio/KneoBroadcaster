package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.PoolStats;
import io.kneo.broadcaster.dto.dashboard.StationStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.stats.PlaylistStats;

import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.service.radio.PlaylistScheduler;
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
    PlaylistScheduler playlistScheduler;

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


        // Add task timeline with progress indicators
        stats.setTaskTimeline(playlistScheduler.getTaskTimeline());

        return Uni.createFrom().item(stats);
    }



    /**
     * Get task timeline progress for scheduler tasks
     *
     * @return Current task timeline data
     */
    public Uni<SchedulerTaskTimeline> getTaskTimeline() {
        return Uni.createFrom().item(playlistScheduler.getTaskTimeline());
    }

    private StationStats createStationStats(String brand, RadioStation station) {
        StationStats stats = new StationStats();
        stats.setBrandName(brand);
        stats.setStatus(station.getStatus());

        if (station.getPlaylist() != null) {
            HLSPlaylist playlist = station.getPlaylist();

            // Get comprehensive stats from the playlist
            PlaylistStats playlistStats = playlist.getStats();

            // Map PlaylistStats to StationStats
            stats.setSegmentsSize(playlistStats.getSegmentCount());
            stats.setLastSegmentKey(playlist.getLastSegmentKey());
            stats.setLastRequested(playlistStats.getLastRequestedSegment());
            stats.setCurrentFragment(playlistStats.getLastRequestedFragmentName());

            // Add new fields from PlaylistStats
            stats.setTotalBytesProcessed(playlistStats.getTotalBytesProcessed());
            stats.setBitrate(playlistStats.getBitrate());
            stats.setQueueSize(playlistStats.getQueueSize());
            stats.setRecentlyPlayedTitles(playlistStats.getRecentlyPlayedTitles());
            stats.setLastUpdated(playlistStats.getTimestamp());
        } else {
            stats.setSegmentsSize(0);
            stats.setRecentlyPlayedTitles(List.of());
        }

        return stats;
    }
}