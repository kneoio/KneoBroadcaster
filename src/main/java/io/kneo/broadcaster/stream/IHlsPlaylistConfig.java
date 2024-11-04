package io.kneo.broadcaster.stream;

public interface IHlsPlaylistConfig {
    int getMaxSegments();
    int getSegmentDuration();
    int getTargetBitrate();
    int getBufferSize();
}
