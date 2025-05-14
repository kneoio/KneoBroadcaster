package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "radio.hls")
public interface HlsPlaylistConfig {

    @WithName("playlist.min.segments")
    @WithDefault("20")
    int getMinSegments();

    @WithName("playlist.segment.max")
    @WithDefault("40")
    int getMaxSegments();

    @WithName("playlist.segment.duration")
    @WithDefault("8")
    int getSegmentDuration();


    @WithName("playmanager.warmup.fragments.quantity")
    @WithDefault("3")
    int getWarmUpFragmentQuantity();
}
