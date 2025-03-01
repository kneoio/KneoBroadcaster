package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.model.SoundFragment;
import lombok.Getter;
@Getter
public class HlsSegment {
    private final int sequenceNumber;
    private final byte[] data;
    private final long timestamp;
    private final int duration;
    private final int bitrate;
    private final long size;
    private final SoundFragment soundFragment;

    public HlsSegment(int sequenceNumber, SoundFragment soundFragment, int duration) {
        this.sequenceNumber = sequenceNumber;
        this.data = soundFragment.getFile();
        this.timestamp = System.currentTimeMillis();
        this.duration = duration;
        this.size = data.length;
        this.bitrate = (int)(size * 8 / (duration * 1000.0));
        this.soundFragment = soundFragment;
    }

    public boolean isExpired(long maxAgeMs) {
        return System.currentTimeMillis() - timestamp > maxAgeMs;
    }
}

