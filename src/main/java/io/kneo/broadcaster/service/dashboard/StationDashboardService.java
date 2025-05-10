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

import java.time.ZonedDateTime;
import java.util.Optional;

@ApplicationScoped
public class StationDashboardService {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(StationDashboardService.class);

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    HlsPlaylistConfig config;

    public Uni<Optional<StationStats>> getStationStats(String brand) {
        return Uni.createFrom().item(() ->
                radioStationPool.getStation(brand)
                        .map(station -> createStationStats(brand, station))
        );
    }

    private StationStats createStationStats(String brand, RadioStation station) {
        StationStats stationStats = new StationStats();
        stationStats.setBrandName(brand);
        stationStats.setStatus(station.getStatus());
        stationStats.setManagedBy(station.getManagedBy());
        if (station.getPlaylist() != null) {
            HLSPlaylist playlist = station.getPlaylist();
            stationStats.setSlideHistory(playlist.getSlideHistory());
            stationStats.setLastSlide(playlist.getLastSlide());
            stationStats.setCurrentWindow(playlist.getKeySet(), playlist.getMainQueue());
            stationStats.setLatestRequestedSeg(playlist.getLatestRequestedSeg());
            stationStats.setSliderStats(SliderStats.builder().scheduledTime(ZonedDateTime.now()).build());
            stationStats.setCurrentWindow(playlist.getCurrentWindow());
            PlaylistManager manager = playlist.getPlaylistManager();
            stationStats.addPeriodicTask(manager.getTaskTimeline());
            stationStats.setPlaylistManagerStats(manager.getStats());
            HLSPlaylistStats hlsSegmentStats = playlist.getStats();
            stationStats.setSongStatistics(hlsSegmentStats.getSongStatistics());
        }

        return stationStats;
    }
}