package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.BrandSoundFragment;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
public class HlsSegment {
    @Setter
    private long sequence;
    private final byte[] data;
    private final long timestamp;
    private final int duration;
    private final int bitrate;
    private final long size;
    private final UUID soundFragmentId;
    private final String songName;
    @Setter
    private BrandSoundFragment sourceFragment;
    @Setter
    private boolean firstSegmentOfFragment = false;

    public HlsSegment(long sequence, byte[] data, int duration, UUID soundFragmentId, String songName, long timestamp) {
        this.sequence = sequence;
        this.data = data;
        this.timestamp = timestamp;
        this.duration = duration;
        this.size = data.length;
        this.bitrate = (int)(size * 8 / (duration * 1000.0));
        this.soundFragmentId = soundFragmentId;
        this.songName = songName;
    }


    public String toString() {
        return String.format("song=%s, duration=%s", songName, duration);
    }
}