package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.stats.SegmentHeartbeat;
import lombok.Getter;

import java.util.Map;

@Getter
public class StreamManagerStats {
    private final Map<Long, HlsSegment> liveSegments;
    private final long requestCount;
    private final boolean heartbeat;
    private final long listenersCount;

    public StreamManagerStats(Map<Long, HlsSegment> liveSegments,
                              long requestCount,
                              HlsPlaylistConfig config,
                              boolean heartbeat) {
        this.liveSegments = liveSegments;
        this.requestCount = requestCount;


        this.heartbeat = heartbeat;

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
