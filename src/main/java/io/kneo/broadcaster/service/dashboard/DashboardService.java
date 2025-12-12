package io.kneo.broadcaster.service.dashboard;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.SchedulerStatsDTO;
import io.kneo.broadcaster.dto.dashboard.StationEntry;
import io.kneo.broadcaster.dto.dashboard.StatsDTO;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.stats.ConfigurationStats;
import io.kneo.broadcaster.service.maintenance.FileMaintenanceService;
import io.kneo.broadcaster.service.scheduler.EventTriggerJob;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
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

    @Inject
    EventTriggerJob eventTriggerJob;

    public Uni<StatsDTO> getInfo() {
        return Uni.createFrom().item(() -> {
            StatsDTO stats = new StatsDTO();
            Collection<Brand> stations = radioStationPool.getOnlineStationsSnapshot();

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
                            station.getStatus() == RadioStationStatus.IDLE)
                    .map(s -> new StationEntry(s.getSlugName()))
                    .collect(Collectors.toList());

            stats.setStations(stationStats);
            stats.setFileMaintenanceStats(fileMaintenanceService.getStats());
            stats.setConfigurationStats(configurationStats);
            stats.setSchedulerStats(buildSchedulerStats());

            return stats;
        });
    }

    private SchedulerStatsDTO buildSchedulerStats() {
        SchedulerStatsDTO schedulerStats = new SchedulerStatsDTO();
        schedulerStats.setSchedulerRunning(true);
        schedulerStats.setSchedulerName("EventTriggerJob");
        schedulerStats.setLastUpdated(LocalDateTime.now());
        schedulerStats.setLastEventTick(eventTriggerJob.getLastTick());
        schedulerStats.setTotalEventsChecked(eventTriggerJob.getTotalEventsChecked());
        schedulerStats.setTotalEventsFired(eventTriggerJob.getTotalEventsFired());
        schedulerStats.setTotalEventErrors(eventTriggerJob.getTotalErrors());
        schedulerStats.setLastFiredEventId(eventTriggerJob.getLastFiredEventId());
        schedulerStats.setLastFiredEventTime(eventTriggerJob.getLastFiredTime());
        return schedulerStats;
    }
}