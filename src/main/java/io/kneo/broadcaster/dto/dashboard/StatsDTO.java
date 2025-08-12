package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.model.stats.ConfigurationStats;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class StatsDTO {
    private int totalStations;
    private int onlineStations;
    private int warmingStations;
    private int offlineStations;
    private int minimumSegments;
    private FileMaintenanceStatsDTO fileMaintenanceStats;
    private int slidingWindowSize;
    private List<StationEntry> stations;
    private ConfigurationStats configurationStats;
    private SchedulerStatsDTO schedulerStats;
}