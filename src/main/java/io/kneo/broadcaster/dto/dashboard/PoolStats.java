package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import lombok.Data;

import java.util.Map;

@Data
public class PoolStats {
    private int totalStations;
    private int onlineStations;
    private int minimumSegments;
    private int slidingWindowSize;
    private Map<String, StationStats> stations;


    // Task progress timeline
    private SchedulerTaskTimeline taskTimeline;
}