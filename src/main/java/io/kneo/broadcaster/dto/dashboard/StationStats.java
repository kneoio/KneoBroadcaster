package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.controller.stream.HLSSongStats;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import lombok.Data;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class StationStats {
    private String brandName;
    private RadioStationStatus status;
    private ManagedBy managedBy;
    private int segmentsSize;
    private PlaylistManagerStats playlistManagerStats;
    private List<SchedulerTaskTimeline> timelines = new ArrayList<>();
    private long totalBytesProcessed;
    private double bitrate;
    private int queueSize;
    private Map<String, HLSSongStats> songStatistics = new LinkedHashMap<>();

    @Getter
    private List<Integer> segmentSizeHistory = new ArrayList<>();

    public void addPeriodicTask(SchedulerTaskTimeline line){
        timelines.add(line);
    }


}