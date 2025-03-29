package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.controller.stream.HLSPlaylist.SegmentSizeSnapshot;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import lombok.Data;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class StationStats {
    private String brandName;
    private RadioStationStatus status;
    private int segmentsSize;
    private long lastSegmentKey;
    private long lastRequested;
    private Instant lastSegmentTimestamp;
    private PlaylistManagerStats playlistManagerStats;
    private List<SchedulerTaskTimeline> timelines = new ArrayList<>();
    private long totalBytesProcessed;
    private double bitrate;
    private int queueSize;
    private Instant lastUpdated;

    @Getter
    private List<SegmentSizeSnapshot> segmentSizeHistory = new ArrayList<>();

    public void addPeriodicTask(SchedulerTaskTimeline line){
        timelines.add(line);
    }
}