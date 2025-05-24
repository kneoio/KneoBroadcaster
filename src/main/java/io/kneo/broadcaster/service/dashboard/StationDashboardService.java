package io.kneo.broadcaster.service.dashboard;

import io.kneo.broadcaster.controller.stream.IStreamManager;
import io.kneo.broadcaster.controller.stream.StreamManagerStats;
import io.kneo.broadcaster.dto.dashboard.StationStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@ApplicationScoped
public class StationDashboardService {

    @Inject
    RadioStationPool radioStationPool;

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
        stationStats.setAlived(station.getCurrentAliveDurationMinutes());
        if (station.getPlaylist() != null) {
            IStreamManager playlist = station.getPlaylist();
            stationStats.setLatestRequestedSeg(playlist.getLatestRequestedSeg());
            PlaylistManager manager = playlist.getPlaylistManager();
            stationStats.setPlaylistManagerStats(manager.getStats());
            StreamManagerStats hlsSegmentStats = playlist.getStats();
            stationStats.setHlsSongStats(hlsSegmentStats.getSongStatistics());
        }

        return stationStats;
    }
}