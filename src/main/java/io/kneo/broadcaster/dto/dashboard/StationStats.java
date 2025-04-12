package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.controller.stream.HLSSongStats;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.model.stats.SliderStats;
import lombok.Getter;
import lombok.Setter;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Setter
public class StationStats {
    @Getter
    private String brandName;
    @Getter
    private RadioStationStatus status;
    @Getter
    private ManagedBy managedBy;
    @Getter
    private PlaylistManagerStats playlistManagerStats;
    private SliderStats sliderStats;
    @Getter
    private List<SchedulerTaskTimeline> timelines = new ArrayList<>();
    @Getter
    private Map<Long, HLSSongStats> songStatistics = new LinkedHashMap<>();
    @Getter
    private List<Integer> segmentSizeHistory = new ArrayList<>();
    @Getter
    private long latestRequestedSeg;
    @Getter
    private List<Long[]> currentWindow;

    public String getNextScheduledTime() {
        return sliderStats.getScheduledTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public void addPeriodicTask(SchedulerTaskTimeline line){
        timelines.add(line);
    }


}