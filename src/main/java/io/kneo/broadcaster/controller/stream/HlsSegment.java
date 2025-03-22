package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

import java.util.UUID;

@Getter
public class HlsSegment {
    private final long sequenceNumber;
    private final byte[] data;
    private final long timestamp;
    private final int duration;
    private final int bitrate;
    private final long size;
    private final UUID soundFragmentId;
    private final String songName;

    public HlsSegment(long sequenceNumber, byte[] data, int duration, UUID soundFragmentId, String songName) {
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.timestamp = System.currentTimeMillis() / 1000;
        this.duration = duration;
        this.size = data.length;
        this.bitrate = (int)(size * 8 / (duration * 1000.0));
        this.soundFragmentId = soundFragmentId;
        this.songName = songName;
    }

    public HlsSegment(long sequenceNumber, byte[] data, int duration, UUID soundFragmentId, String songName, long timestamp) {
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.timestamp = timestamp;
        this.duration = duration;
        this.size = data.length;
        this.bitrate = (int)(size * 8 / (duration * 1000.0));
        this.soundFragmentId = soundFragmentId;
        this.songName = songName;
    }

    public boolean isExpired(long maxAgeMs) {
        return System.currentTimeMillis() - (timestamp * 1000) > maxAgeMs;
    }
}