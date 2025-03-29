package io.kneo.broadcaster.service.manipulation;

import io.kneo.broadcaster.config.BroadcasterConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import net.bramp.ffmpeg.FFmpeg;

import java.io.IOException;

@ApplicationScoped
public class FFmpegProvider {
    @Inject
    BroadcasterConfig config;
    private FFmpeg ffmpeg;

    @PostConstruct
    void init() throws IOException {
        this.ffmpeg = new FFmpeg(config.getFfmpegPath());
    }

    public FFmpeg getFFmpeg() {
        return ffmpeg;
    }
}