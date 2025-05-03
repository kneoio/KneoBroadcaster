package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.Stats;
import io.kneo.broadcaster.dto.dashboard.StationEntry;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.stats.ConfigurationStats;
import io.kneo.broadcaster.service.filemaintainance.FileMaintenanceService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
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
        HashMap<String, RadioStation> pool = radioStationPool.getPool();
        Stats stats = new Stats();
        stats.setTotalStations(pool.size()); //TODO temporary
        stats.setMinimumSegments(config.getMinSegments());
        stats.setSlidingWindowSize(config.getMaxSegments());
        stats.setOnlineStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE || station.getStatus() == RadioStationStatus.ON_LINE_WELL)
                .count());
        stats.setWarmingStations((int) pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.WARMING_UP)
                .count());
        List<StationEntry> stationStats = pool.values().stream()
                .filter(station -> station.getStatus() == RadioStationStatus.ON_LINE
                        || station.getStatus() == RadioStationStatus.ON_LINE_WELL
                        || station.getStatus() == RadioStationStatus.WARMING_UP
                )
                .map(s -> new StationEntry(s.getSlugName()))
                .collect(Collectors.toList());
        stats.setStations(stationStats);
        stats.setFileMaintenanceStats(fileMaintenanceService.getStats());
        stats.setConfigurationStats(configurationStats);

        return Uni.createFrom().item(stats);
    }
}