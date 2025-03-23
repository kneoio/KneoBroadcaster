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
    @WithDefault("200")
    int getMinSegments();

    @WithName("playlist.segment.duration")
    @WithDefault("28")
    int getSegmentDuration();

    @WithName("playlist.segment.duration")
    @WithDefault("5")
    int getSegmentWindow();

    @WithName("playlist.segment.max")
    @WithDefault("30")
    int getMaxSegments();
}
