package io.kneo.broadcaster.service.stream;

import lombok.Getter;

@Getter
public class HLSSongStats {
    private final String title;
    private final long segmentTimestamp;
    private final long requestCount;

    public HLSSongStats(String title, long segmentTimestamp, long requestCount) {
        this.title = title;
        this.segmentTimestamp = segmentTimestamp;
        this.requestCount = requestCount;
    }
}
