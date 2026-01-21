package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;

@ConfigMapping(prefix = "broadcaster")
public interface BroadcasterConfig {
    @WithName("host")
    @WithDefault("localhost")
    String getHost();

    @WithName("agent.url")
    @WithDefault("http://localhost:38799")
    String getAgentUrl();

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
    @WithDefault("/tmp/file-uploads")
    String getQuarkusFileUploadsPath();

    @WithName("ffmpeg.path")
    @WithDefault("ffmpeg")
    String getFfmpegPath();

    @WithName("ffprobe.path")
    @WithDefault("ffprobe")
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

    @WithName("station.whitelist")
    @WithDefault("aye-ayes-ear,lumisonic,sacana")
    List<String> getStationWhitelist();

    @WithName("agent.api-key")
    String getAgentApiKey();

    @WithName("anthropic.api-key")
    String getAnthropicApiKey();

    @WithName("elevenlabs.api-key")
    String getElevenLabsApiKey();

    @WithName("elevenlabs.voice-id")
    @WithDefault("nZ5WsS2E2UAALki8m2V6")
    String getElevenLabsVoiceId();

    @WithName("elevenlabs.model-id")
    @WithDefault("eleven_v3")
    String getElevenLabsModelId();

    @WithName("elevenlabs.output-format")
    @WithDefault("mp3_44100_128")
    String getElevenLabsOutputFormat();

    @WithName("modelslab.api-key")
    String getModelslabApiKey();
}