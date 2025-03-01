package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "radio.hls")
public interface HlsPlaylistConfig {
    @WithName("max.segments")
    @WithDefault("100")
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
    @WithDefault("1.5")
    double getBufferSizeMultiplier();

    default int getBufferSizeKb() {
        // 128 kbps * 10 seconds = 1280 kb
        // Convert to KB (divide by 8) = 160 KB
        // Add 50% overhead for MPEGTS container and safety margin
        return (int)((getBitrate() * getSegmentDuration() * 1.5) / 8);
    }
    @WithName("basedir")
    @WithDefault("uploads")
    String getBaseDir();

}
