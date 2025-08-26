package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.live.SongMetadata;
import lombok.Getter;
import lombok.Setter;

@Getter
public class HlsSegment {
    @Setter
    private long sequence;
    private final byte[] data;
    private final long timestamp;
    private final int duration;
    private final int bitrate;
    private final long size;
    private final SongMetadata songMetadata;
    @Setter
    private LiveSoundFragment liveSoundFragment;
    @Setter
    private boolean firstSegmentOfFragment = false;

    public HlsSegment(long sequence, byte[] data, int duration, SongMetadata songMetadata, long timestamp) {
        this.sequence = sequence;
        this.data = data;
        this.timestamp = timestamp;
        this.duration = duration;
        this.size = data.length;
        this.bitrate = (int)(size * 8 / (duration * 1000.0));
        this.songMetadata = songMetadata;
    }


    public String toString() {
        return String.format("song=%s, duration=%s", songMetadata, duration);
    }
}