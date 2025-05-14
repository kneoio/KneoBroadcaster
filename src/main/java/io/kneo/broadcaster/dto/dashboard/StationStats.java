package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.controller.stream.HLSSongStats;
import io.kneo.broadcaster.controller.stream.KeySet;
import io.kneo.broadcaster.controller.stream.PlaylistFragmentRange;
import io.kneo.broadcaster.controller.stream.SlideEvent;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.model.stats.SliderStats;
import lombok.Getter;
import lombok.Setter;

import java.time.ZonedDateTime;
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
    private long alived;
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
    private long latestRequestedSeg;
    @Getter
    private List<Long[]> currentWindow;
    private Map<Integer, PlaylistFragmentRange> mainQueue;
    @Getter
    private ZonedDateTime lastSlide;
    @Getter
    private List<SlideEvent> slideHistory;

    public void addPeriodicTask(SchedulerTaskTimeline line){
        timelines.add(line);
    }

    public void setCurrentWindow(KeySet keySet, Map<Integer, PlaylistFragmentRange> mainQueue){
        try {
            currentWindow = List.of(
                    extractRange(keySet.current()),
                    extractRange(keySet.next())
                    //   extractRange(keySet.future())
            );
        } catch (Exception e) {
            currentWindow = List.of(new Long[0], new Long[0], new Long[0]);
        }
    }

    private Long[] extractRange(Object key) {
        try {
            Object entry = mainQueue != null ? mainQueue.get(key) : null;
            return entry != null ? (Long[])entry.getClass().getMethod("getRange").invoke(entry) : new Long[0];
        } catch (Exception e) {
            return new Long[0];
        }
    }

    public String getAliveTimeInHours() {
        int hours = (int) (alived / 60);
        int minutes = (int) (alived % 60);
        return String.format("%02d:%02d", hours, minutes);
    }
}