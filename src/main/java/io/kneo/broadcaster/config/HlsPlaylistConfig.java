package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "radio.hls")
public interface HlsPlaylistConfig {

    @WithName("playlist.min.segments")
    @WithDefault("50")
    int getMinSegments();

    @WithName("playlist.segment.duration")
    @WithDefault("6")
    int getSegmentDuration();

    @WithName("playlist.segment.max")
    @WithDefault("70")
    int getMaxSegments();

    @WithName("playmanager.warmup.fragments.quantity")
    @WithDefault("3")
    int getWarmUpFragmentQuantity();
}
