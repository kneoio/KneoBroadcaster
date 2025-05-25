package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.controller.stream.HLSSongStats;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.model.stats.SegmentTimelineDisplay;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
public class StationStats {
    @Getter
    private String brandName;
    @Getter
    private RadioStationStatus status;
    private long alived;
    @Getter
    private ManagedBy managedBy;
    @Getter
    private PlaylistManagerStats playlistManagerStats;
    @Getter
    private List<SchedulerTaskTimeline> timelines = new ArrayList<>();
    @Getter
    private HLSSongStats hlsSongStats;
    @Getter
    private long latestRequestedSeg;
    @Getter
    private SegmentTimelineDisplay timeline;
    @Getter
    private long listenersCount;

    public String getAliveTimeInHours() {
        int hours = (int) (alived / 60);
        int minutes = (int) (alived % 60);
        return String.format("%02d:%02d", hours, minutes);
    }
}