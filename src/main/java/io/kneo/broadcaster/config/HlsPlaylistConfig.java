package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "radio.hls")
public interface HlsPlaylistConfig {

    @WithName("radio.ffmpeg.path")
    @WithDefault("/usr/bin/ffmpeg")
    String  getFFMpegPath();

    @WithName("playlist.min.segments")
    @WithDefault("100")
    int getMinSegments();

    @WithName("playlist.segment.duration")
    @WithDefault("6")
    int getSegmentDuration();

    @WithName("playlist.segment.max")
    @WithDefault("100")
    int getMaxSegments();

    @WithName("playmanager.reserved.fragments.max")
    @WithDefault("10")
    int getMaxReservedFragments();
}
