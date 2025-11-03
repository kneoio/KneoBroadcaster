package io.kneo.broadcaster.service.stream;

import java.util.Map;

public record StreamManagerStats(Map<Long, HlsSegment> liveSegments, boolean heartbeat) {

    public HLSSongStats getSongStatistics() {
        return liveSegments.values().stream()
                .map(segment -> new HLSSongStats(
                        segment.getSongMetadata()
                ))
                .findFirst()
                .orElse(null);
    }
}
