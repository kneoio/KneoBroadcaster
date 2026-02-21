package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.service.stats.HLSSongStats;

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
