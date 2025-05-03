package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "broadcaster")
public interface BroadcasterConfig {
    @WithName("host")
    String getHost();

    @WithName("controller.upload.files.path")
    @WithDefault("controller-uploads")
    String getPathUploads();

    @WithName("merged.files.path")
    @WithDefault("merged")
    String getPathForMerged();

    @WithName("segmented.files.path")
    @WithDefault("segmented")
    String getSegmentationOutputDir();

    @WithName("external.upload.files.path")
    @WithDefault("external_uploads")
    String getPathForExternalServiceUploads();

    @WithName("ffmpeg.path")
    String getFfmpegPath();
}