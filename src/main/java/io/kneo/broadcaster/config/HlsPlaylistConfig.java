package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "radio.hls")
public interface HlsPlaylistConfig {
    @WithName("max.segments")
    @WithDefault("10")
    int getMaxSegments();

    @WithName("segment.duration")
    @WithDefault("10")
    int getSegmentDuration();

    @WithName("bitrate")
    @WithDefault("128")
    int getBitrate();

    @WithName("cleanup.interval")
    @WithDefault("30")
    int getCleanupInterval();

    @WithName("buffer.size.multiplier")
    @WithDefault("1")
    double getBufferSizeMultiplier();

    @WithName("monitoring.enabled")
    @WithDefault("true")
    boolean isMonitoringEnabled();

    default int getBufferSizeKb() {
        return (int)(getBitrate() * getSegmentDuration() * getBufferSizeMultiplier() / 8);
    }
}
