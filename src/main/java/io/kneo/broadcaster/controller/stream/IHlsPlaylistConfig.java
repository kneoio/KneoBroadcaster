package io.kneo.broadcaster.controller.stream;

public interface IHlsPlaylistConfig {
    int getMaxSegments();
    int getSegmentDuration();
    int getTargetBitrate();
    int getBufferSize();
}
