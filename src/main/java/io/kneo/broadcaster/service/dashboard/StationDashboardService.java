package io.kneo.broadcaster.service.dashboard;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.controller.stream.HLSPlaylistStats;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.StationStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Optional;

@ApplicationScoped
public class StationDashboardService {

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    HlsPlaylistConfig config;

    public Uni<Optional<StationStats>> getStationStats(String stationId) {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        if (pool.containsKey(stationId)) {
            RadioStation station = pool.get(stationId);
            StationStats stats = createStationStats(stationId, station);
            return Uni.createFrom().item(Optional.of(stats));
        }
        return Uni.createFrom().item(Optional.empty());
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
            HLSPlaylistStats hlsSegmentStats = playlist.getStats();
            stationStats.setSongStatistics(hlsSegmentStats.getSongStatistics());
            stationStats.setSegmentsSize(hlsSegmentStats.getSegmentCount());
            stationStats.setTotalBytesProcessed(hlsSegmentStats.getTotalBytesProcessed());
            stationStats.setBitrate(hlsSegmentStats.getBitrate());
            stationStats.setQueueSize(hlsSegmentStats.getQueueSize());
        } else {
            stationStats.setSegmentsSize(0);
        }

        return stationStats;
    }

    public Uni<Boolean> isStationOnline(String stationId) {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        return Uni.createFrom().item(
                pool.containsKey(stationId) &&
                        pool.get(stationId).getStatus() == RadioStationStatus.ON_LINE
        );
    }

    public Uni<RadioStationStatus> getStationStatus(String stationId) {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        if (pool.containsKey(stationId)) {
            return Uni.createFrom().item(pool.get(stationId).getStatus());
        }
        return Uni.createFrom().item(RadioStationStatus.OFF_LINE);
    }

    public Uni<Boolean> stationExists(String stationId) {
        return Uni.createFrom().item(radioStationPool.getPool().containsKey(stationId));
    }
}