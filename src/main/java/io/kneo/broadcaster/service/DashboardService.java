package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.StationEntry;
import io.kneo.broadcaster.dto.dashboard.Stats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.stats.ConfigurationStats;
import io.kneo.broadcaster.service.filemaintainance.FileMaintenanceService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;


@ApplicationScoped
public class DashboardService {

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    FileMaintenanceService fileMaintenanceService;

    @Inject
    ConfigurationStats configurationStats;

    public Uni<Stats> getInfo() {
        return Uni.createFrom().item(() -> {
            Stats stats = new Stats();
            Collection<RadioStation> stations = radioStationPool.getOnlineStationsSnapshot();

            stats.setTotalStations(stations.size());
            stats.setMinimumSegments(config.getMinSegments());
            stats.setSlidingWindowSize(config.getMaxSegments());

            stats.setOnlineStations((int) stations.stream()
                    .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE ||
                            station.getStatus() == RadioStationStatus.IDLE)
                    .count());

            stats.setWarmingStations((int) stations.stream()
                    .filter(station -> station.getStatus() == RadioStationStatus.WARMING_UP)
                    .count());

            List<StationEntry> stationStats = stations.stream()
                    .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE ||
                            station.getStatus() == RadioStationStatus.WARMING_UP ||
                            station.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR ||
                            station.getStatus() == RadioStationStatus.IDLE)
                    .map(s -> new StationEntry(s.getSlugName()))
                    .collect(Collectors.toList());

            stats.setStations(stationStats);
            stats.setFileMaintenanceStats(fileMaintenanceService.getStats());
            stats.setConfigurationStats(configurationStats);

            return stats;
        });
    }
}