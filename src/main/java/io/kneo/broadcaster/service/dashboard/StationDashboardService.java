package io.kneo.broadcaster.service.dashboard;

import io.kneo.broadcaster.dto.dashboard.StationStatsDTO;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.StreamManagerStats;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@ApplicationScoped
public class StationDashboardService {

    @Inject
    RadioStationPool radioStationPool;

    public Uni<Optional<StationStatsDTO>> getStationStats(String brand) {
        return Uni.createFrom().item(() ->
                radioStationPool.getStation(brand)
                        .map(station -> createStationStats(brand, station))
        );
    }

    private StationStatsDTO createStationStats(String brand, RadioStation station) {
        StationStatsDTO stationStats = new StationStatsDTO();
        stationStats.setBrandName(brand);
        stationStats.setStatus(station.getStatus());
        stationStats.setStatusHistory(station.getStatusHistory());
        stationStats.setManagedBy(station.getManagedBy());

        if (station.getStreamManager() != null) {
            IStreamManager streamManager = station.getStreamManager();
            stationStats.setLatestRequestedSeg(streamManager.getLatestRequestedSeg());
            StreamManagerStats hlsSegmentStats = streamManager.getStats();
            stationStats.setTimeline(hlsSegmentStats.getSegmentTimelineDisplay());
            stationStats.setSongStatistics(hlsSegmentStats.getSongStatistics());
            stationStats.setCurrentListeners(hlsSegmentStats.getListenersCount());
            PlaylistManager playlistManager = streamManager.getPlaylistManager();
            stationStats.setPlaylistManagerStats(playlistManager.getStats());
        }

        return stationStats;
    }
}