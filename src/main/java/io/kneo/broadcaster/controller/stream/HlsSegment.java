package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

import java.util.UUID;

@Getter
public class HlsSegment {
    private final byte[] data;
    private final long timestamp;
    private final int duration;
    private final int bitrate;
    private final long size;
    private final UUID soundFragmentId;
    private final String songName;
    private final String uniqueUri; // Add this field for unique URI

    public HlsSegment(byte[] data, int duration, UUID soundFragmentId, String songName, long timestamp) {
        this.data = data;
        this.timestamp = timestamp;
        this.duration = duration;
        this.size = data.length;
        this.bitrate = (int)(size * 8 / (duration * 1000.0));
        this.soundFragmentId = soundFragmentId;
        this.songName = songName;
        // Generate a unique URI using the fragment ID and timestamp
        this.uniqueUri = "segments/" + soundFragmentId.toString().substring(0, 8) + "_" + timestamp + ".ts";
    }

    public String toString() {
        return String.format("song=%s, duration=%s", songName, duration);
    }
}