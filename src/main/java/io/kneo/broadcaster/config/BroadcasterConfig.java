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

    @WithName("quarkus.file.upload.path")
    String getQuarkusFileUploadsPath();

    @WithName("ffmpeg.path")
    String getFfmpegPath();

    @WithName("ffprobe.path")
    String getFfprobePath();

    @WithName("audio.sample-rate")
    @WithDefault("44100")
    int getAudioSampleRate();

    @WithName("audio.channels")
    @WithDefault("stereo")
    String getAudioChannels();

    @WithName("audio.output-format")
    @WithDefault("mp3")
    String getAudioOutputFormat();

    @WithName("audio.max-silence-duration")
    @WithDefault("3600")
    int getMaxSilenceDuration();
}