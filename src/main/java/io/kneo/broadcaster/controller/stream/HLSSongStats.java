package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

@Getter
public class HLSSongStats {
    private final String title;
    private final int segmentCount;
    private final int totalDuration;
    private final long totalSize;
    private final int averageBitrate;
    private final int requestCount;
    private final long start;
    private final long end;

    public HLSSongStats(String title, long start, long end, int segmentCount, int totalDuration,
                        long totalSize, int averageBitrate, int requestCount) {
        this.title = title;
        this.start = start;
        this.end = end;
        this.segmentCount = segmentCount;
        this.totalDuration = totalDuration;
        this.totalSize = totalSize;
        this.averageBitrate = averageBitrate;
        this.requestCount = requestCount;
    }
}
