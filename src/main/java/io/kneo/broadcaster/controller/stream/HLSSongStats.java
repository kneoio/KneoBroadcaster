package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

@Getter
public class HLSSongStats {
    private final String title;
    private final long segmentTimestamp;
    private final int requestCount;

    public HLSSongStats(String title, long segmentTimestamp, int requestCount) {
        this.title = title;
        this.segmentTimestamp = segmentTimestamp;
        this.requestCount = requestCount;
    }
}
