package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

import java.util.UUID;

@Getter
public class HlsSegment {
    private final int sequenceNumber;
    private final byte[] data;
    private final long timestamp;
    private final int duration;
    private final int bitrate;
    private final long size;
    private final UUID soundFragmentId;

    public HlsSegment(int sequenceNumber, byte[] data, int duration, UUID soundFragmentId) {
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
        this.duration = duration;
        this.size = data.length;
        this.bitrate = (int)(size * 8 / (duration * 1000.0));
        this.soundFragmentId = soundFragmentId;
    }

    public boolean isExpired(long maxAgeMs) {
        return System.currentTimeMillis() - timestamp > maxAgeMs;
    }
}

