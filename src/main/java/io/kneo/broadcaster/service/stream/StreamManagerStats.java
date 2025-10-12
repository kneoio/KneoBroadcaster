package io.kneo.broadcaster.service.stream;

import lombok.Getter;

import java.util.Map;

@Getter
public class StreamManagerStats {
    private final Map<Long, HlsSegment> liveSegments;
    private final long requestCount;
    private final boolean heartbeat;
    private final long listenersCount = 0;

    public StreamManagerStats(Map<Long, HlsSegment> liveSegments,
                              long requestCount,
                              boolean heartbeat) {
        this.liveSegments = liveSegments;
        this.requestCount = requestCount;
        this.heartbeat = heartbeat;
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
