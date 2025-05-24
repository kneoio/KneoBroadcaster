package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

import java.util.Map;
import java.util.TreeMap;

@Getter
public class StreamManagerStats {
    private final Map<Long, HlsSegment> liveSegments;
    private final TreeMap<String, Integer> songRequestCounts = new TreeMap<>();

    public StreamManagerStats(Map<Long, HlsSegment> liveSegments) {
        this.liveSegments = liveSegments;
    }

    public HLSSongStats getSongStatistics() {
        return liveSegments.values().stream()
                .map(segment -> {
                    String songName = segment.getSongName();
                    long segmentTimestamp = segment.getTimestamp();
                    int requestCount = songRequestCounts.getOrDefault(songName, 0);
                    return new HLSSongStats(songName, segmentTimestamp, requestCount);
                })
                .findFirst()
                .orElse(null);
    }
}