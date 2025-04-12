package io.kneo.broadcaster.service.dashboard;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.controller.stream.HLSPlaylistStats;
import io.kneo.broadcaster.dto.dashboard.StationStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.stats.SliderStats;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
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

    public Uni<Optional<StationStats>> getStationStats(String brand) {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        if (pool.containsKey(brand)) {
            RadioStation station = pool.get(brand);
            StationStats stats = createStationStats(brand, station);
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
            stationStats.setLatestRequestedSeg(playlist.getLatestRequestedSeg());
            stationStats.setCurrentWindow(playlist.getCurrentWindow());
            stationStats.setSliderStats(SliderStats.builder()
                    .scheduledTime(playlist.getWindowSliderTimer()
                            .getScheduledTime(brand))
                    .build());
            PlaylistManager manager = playlist.getPlaylistManager();
            stationStats.addPeriodicTask(manager.getTaskTimeline());
            stationStats.setPlaylistManagerStats(manager.getStats());
            stationStats.setSegmentSizeHistory(playlist.getSegmentSizeHistory());
            HLSPlaylistStats hlsSegmentStats = playlist.getStats();
            stationStats.setSongStatistics(hlsSegmentStats.getSongStatistics());
        }

        return stationStats;
    }
}