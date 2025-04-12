package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class PoolStats {
    private int totalStations;
    private int onlineStations;
    private int warmingStations;
    private int offlineStations;
    private int minimumSegments;
    private int slidingWindowSize;
    private List<StationEntry> stations;

    private List<SchedulerTaskTimeline> timelines = new ArrayList<>();

    public void addPeriodicTask(SchedulerTaskTimeline line){
        timelines.add(line);
    }

}