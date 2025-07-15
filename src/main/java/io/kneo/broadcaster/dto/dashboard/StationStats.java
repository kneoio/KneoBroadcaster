package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.service.stream.HLSSongStats;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.SegmentTimelineDisplay;
import lombok.Getter;
import lombok.Setter;

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
    private SegmentTimelineDisplay timeline;  // This contains the segment sequences
    @Getter
    private HLSSongStats songStatistics;     // Renamed from hlsSongStats to match frontend
    @Getter
    private long latestRequestedSeg;
    @Getter
    private long currentListeners;           // Renamed from listenersCount to match frontend

    public String getAliveTimeInHours() {
        int hours = (int) (alived / 60);
        int minutes = (int) (alived % 60);
        return String.format("%02d:%02d", hours, minutes);
    }
}