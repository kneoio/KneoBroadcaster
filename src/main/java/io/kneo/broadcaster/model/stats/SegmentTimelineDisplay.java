package io.kneo.broadcaster.model.stats;

import java.util.List;

public record SegmentTimelineDisplay(
        List<Long> pastSegmentSequences,
        List<Long> visibleSegmentSequences,
        List<Long> upcomingSegmentSequences
) {
    public static SegmentTimelineDisplay empty() {
        return new SegmentTimelineDisplay(List.of(), List.of(), List.of());
    }
}