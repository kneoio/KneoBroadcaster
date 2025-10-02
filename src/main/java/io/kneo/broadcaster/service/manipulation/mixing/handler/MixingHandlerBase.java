package io.kneo.broadcaster.service.manipulation.mixing.handler;

import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
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

    protected Uni<WavFile> convertToWav(String inputPath) {
        return Uni.createFrom().item(() -> {
            File inputFile = new File(inputPath);

            try {
                if (inputPath.toLowerCase().endsWith(".wav")) {
                    try (AudioInputStream ais = AudioSystem.getAudioInputStream(inputFile)) {
                        AudioFormat format = ais.getFormat();
                        long frames = ais.getFrameLength();
                        double duration = (double) frames / format.getFrameRate();
                        return new WavFile(inputFile, duration);
                    }
                }

                String outputPath = inputPath + "_tmp.wav";
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-y",
                        "-i", inputPath,
                        "-ar", "44100",
                        "-ac", "2",
                        "-sample_fmt", "s16",
                        outputPath
                );

                int exitCode = pb.inheritIO().start().waitFor();
                if (exitCode != 0) {
                    throw new IOException("FFmpeg failed with exit code " + exitCode);
                }

                File outputFile = new File(outputPath);
                try (AudioInputStream ais = AudioSystem.getAudioInputStream(outputFile)) {
                    AudioFormat format = ais.getFormat();
                    long frames = ais.getFrameLength();
                    double duration = (double) frames / format.getFrameRate();
                    return new WavFile(outputFile, duration);
                }
            } catch (IOException | UnsupportedAudioFileException | InterruptedException e) {
                throw new RuntimeException("Failed to convert to wav: " + inputPath, e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    protected Uni<File> normalizeAndCompress(String inputPath) {
        return Uni.createFrom().item(() -> {
            try {
                String outputPath = inputPath + "_norm.wav";
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-y",
                        "-i", inputPath,
                        "-af", "loudnorm,acompressor",
                        "-ar", "44100",
                        "-ac", "2",
                        "-sample_fmt", "s16",
                        outputPath
                );

                int exitCode = pb.inheritIO().start().waitFor();
                if (exitCode != 0) {
                    throw new IOException("FFmpeg normalize/compress failed with exit code " + exitCode);
                }
                return new File(outputPath);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to normalize/compress: " + inputPath, e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }



}