package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig; // Import HlsPlaylistConfig
import io.kneo.broadcaster.model.stats.SegmentTimelineDisplay;
// Assuming HLSSongStats and HlsSegment are defined in your project.
import lombok.Getter;

import java.util.Map;

@Getter
public class StreamManagerStats {
    private final Map<Long, HlsSegment> liveSegments;
    private final long requestCount;
    private final SegmentTimelineDisplay segmentTimelineDisplay;
    private final long listenersCount;

    public StreamManagerStats(Map<Long, HlsSegment> liveSegments,
                              SegmentTimelineDisplay segmentTimelineDisplay,
                              long requestCount,
                              HlsPlaylistConfig config) {
        this.liveSegments = liveSegments;
        this.requestCount = requestCount;
        this.segmentTimelineDisplay = segmentTimelineDisplay;

        int segmentDurationSeconds = 0;
        if (config != null) {
            segmentDurationSeconds = config.getSegmentDuration();
        }

        if (segmentDurationSeconds > 0) {
            long segmentsPerUserIn5Min = (5L * 60L) / segmentDurationSeconds;
            if (segmentsPerUserIn5Min > 0) {
                this.listenersCount = this.requestCount / segmentsPerUserIn5Min;
            } else {
                this.listenersCount = (this.requestCount > 0) ? -1L : 0L;
            }
        } else {
            this.listenersCount = (this.requestCount > 0) ? -1L : 0L;
        }
    }

    public HLSSongStats getSongStatistics() {
        return liveSegments.values().stream()
                .map(segment -> new HLSSongStats(
                        segment.getSongMetadata(),
                        segment.getTimestamp(),
                        this.requestCount
                ))
                .findFirst()
                .orElse(null);
    }
}