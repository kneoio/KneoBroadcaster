package io.kneo.broadcaster.service.manipulation.mixing.handler;

import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class MixingHandlerBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixingHandlerBase.class);
    protected final FFmpegExecutor executor;
    protected final FFprobe ffprobe;

    public MixingHandlerBase(FFmpegProvider fFmpegProvider) throws IOException {
        this.executor = new FFmpegExecutor(fFmpegProvider.getFFmpeg());
        this.ffprobe = fFmpegProvider.getFFprobe();
    }

    protected double getAudioDuration(String filePath) throws IOException {
        try {
            FFmpegProbeResult probeResult = ffprobe.probe(filePath);
            if (probeResult.getFormat() != null) {
                return probeResult.getFormat().duration;
            }

            if (probeResult.getStreams() != null) {
                for (FFmpegStream stream : probeResult.getStreams()) {
                    if ("audio".equals(stream.codec_type.toString())) {
                        return stream.duration;
                    }
                }
            }

            LOGGER.warn("Could not determine duration for file: {}", filePath);
            return 0.0;

        } catch (Exception e) {
            LOGGER.error("Error getting audio duration for {}: {}", filePath, e.getMessage());
            throw new IOException("Failed to get audio duration", e);
        }
    }

    protected String getFadeType(int fadeCurve) {
        return switch (fadeCurve) {
            case 1 -> "exp";   // Exponential
            case -1 -> "log";  // Logarithmic
            default -> "tri";
        };
    }
}