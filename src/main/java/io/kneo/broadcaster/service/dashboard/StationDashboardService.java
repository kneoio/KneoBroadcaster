package io.kneo.broadcaster.service.dashboard;

import io.kneo.broadcaster.dto.dashboard.StationStatsDTO;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.StreamManagerStats;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@ApplicationScoped
public class StationDashboardService {

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    AiHelperService aiHelperService;

    public Uni<Optional<StationStatsDTO>> getStationStats(String brand) {
        return Uni.createFrom().item(() -> radioStationPool.getStation(brand))
                .flatMap(optionalStation -> {
                    if (optionalStation.isEmpty()) {
                        return Uni.createFrom().item(Optional.empty());
                    }
                    RadioStation station = optionalStation.get();
                    StationStatsDTO stats = createStationStats(brand, station);
                    
                    return aiHelperService.getAiDjStats(station)
                            .onFailure().recoverWithItem(() -> null)
                            .map(aiDjStats -> {
                                stats.setAiDjStats(aiDjStats);
                                return Optional.of(stats);
                            });
                });
    }

    private StationStatsDTO createStationStats(String brand, RadioStation station) {
        StationStatsDTO stationStats = new StationStatsDTO();
        stationStats.setBrandName(brand);
        stationStats.setStatus(station.getStatus());
        stationStats.setStatusHistory(station.getStatusHistory());
        stationStats.setManagedBy(station.getManagedBy());

        ZoneId zone = station.getTimeZone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        String timePart = now.format(DateTimeFormatter.ofPattern("HH- mm"));
        String city = zone.getId().substring(zone.getId().lastIndexOf('/') + 1);
        stationStats.setRealTime(timePart + " " + city);

        if (station.getStreamManager() != null) {
            IStreamManager streamManager = station.getStreamManager();
            StreamManagerStats hlsSegmentStats = streamManager.getStats();
            stationStats.setHeartbeat(hlsSegmentStats.heartbeat());
            stationStats.setSongStatistics(hlsSegmentStats.getSongStatistics());
            PlaylistManager playlistManager = streamManager.getPlaylistManager();
            stationStats.setPlaylistManagerStats(playlistManager.getStats());
        }

        return stationStats;
    }
}