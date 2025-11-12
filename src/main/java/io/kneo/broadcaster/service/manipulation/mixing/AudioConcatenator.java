package io.kneo.broadcaster.service.manipulation.mixing;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class AudioConcatenator {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioConcatenator.class);
    private static final int SAMPLE_RATE = 44100;

    private final FFmpegExecutor executor;
    private final FFprobe ffprobe;
    private final String outputDir;

    @Inject
    public AudioConcatenator(BroadcasterConfig config, FFmpegProvider ffmpeg) throws AudioMergeException {
        this.outputDir = config.getPathForMerged();

        try {
            this.executor = new FFmpegExecutor(ffmpeg.getFFmpeg());
            this.ffprobe = new FFprobe(config.getFfprobePath());
        } catch (IOException e) {
            throw new AudioMergeException("Failed to initialize FFmpeg executor", e);
        }

        initializeOutputDirectory();
    }

    private void initializeOutputDirectory() {
        new File(outputDir).mkdirs();
        cleanupTempFiles();
    }

    private void cleanupTempFiles() {
        try {
            File directory = new File(outputDir);
            File[] files = directory.listFiles((dir, name) -> name.startsWith("silence_") || name.startsWith("temp_song_"));
            if (files != null) {
                for (File file : files) {
                    Files.deleteIfExists(file.toPath());
                    LOGGER.info("Deleted temporary file: {}", file.getName());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Error cleaning up temporary files", e);
        }
    }

    public Uni<String> concatenate(String firstPath, String secondPath, String outputPath,
                                   ConcatenationType mixingType, double gainValue) {
        return Uni.createFrom().item(() -> {
            try {
                LOGGER.info("Concatenating with mixing type: {}, gain: {}s", mixingType, gainValue);

                return switch (mixingType) {
                    case DIRECT_CONCAT -> directConcatenation(firstPath, secondPath, outputPath, gainValue);
                    case CROSSFADE -> createCrossfadeMix(firstPath, secondPath, outputPath, gainValue);
                    case VOLUME_CONCAT -> volumeConcatenation(firstPath, secondPath, outputPath, gainValue);
                };
            } catch (Exception e) {
                LOGGER.error("Error in concatenateWithMixing: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to concatenate with mixing", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private String directConcatenation(String firstPath, String secondPath, String outputPath, double gainValue) {
        FFmpegBuilder builder = new FFmpegBuilder()
                .setInput(firstPath)
                .addInput(secondPath)
                .setComplexFilter(String.format(
                        "[0]volume=%.2f,aresample=async=1,aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo[first];" +
                                "[1]aresample=async=1,aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo[second];" +
                                "[first][second]concat=n=2:v=0:a=1",
                        gainValue))
                .addOutput(outputPath)
                .setAudioCodec("pcm_s16le")
                .setAudioSampleRate(SAMPLE_RATE)
                .setAudioChannels(2)
                .done();

        executor.createJob(builder).run();
        return outputPath;
    }

    private String createCrossfadeMix(String firstPath, String secondPath, String outputPath,
                                      double crossfadeDuration) throws Exception {
        double firstDuration = getAudioDuration(firstPath);
        double crossfadeStart = Math.max(0, firstDuration - crossfadeDuration);

        String filterComplex = String.format(
                "[0:a]silenceremove=start_periods=1:start_threshold=-40dB:stop_periods=-1:stop_threshold=-40dB:detection=peak," +
                        "atrim=start=0:end=%.2f,aresample=async=1,asetpts=N/SR/TB,afade=t=out:st=%.2f:d=%.2f[a1];" +
                        "[1:a]silenceremove=start_periods=1:start_threshold=-40dB:stop_periods=-1:stop_threshold=-40dB:detection=peak," +
                        "aresample=async=1,atrim=start=0,asetpts=N/SR/TB,afade=t=in:st=0:d=%.2f,adelay=%.0f|%.0f[a2];" +
                        "[a1][a2]amix=inputs=2:duration=longest:dropout_transition=0",
                crossfadeStart + 0.15,  // small safety margin before fade-out
                crossfadeStart, crossfadeDuration,
                crossfadeDuration,
                crossfadeStart * 1000, crossfadeStart * 1000
        );

        FFmpegBuilder builder = new FFmpegBuilder()
                .addExtraArgs("-err_detect", "ignore_err")
                .addExtraArgs("-fflags", "+genpts")
                .addExtraArgs("-avoid_negative_ts", "1")
                .setInput(firstPath)
                .addInput(secondPath)
                .setComplexFilter(filterComplex)
                .addOutput(outputPath)
                .setAudioCodec("pcm_s16le")
                .setAudioSampleRate(SAMPLE_RATE)
                .setAudioChannels(2)
                .done();

        executor.createJob(builder).run();
        return outputPath;
    }

    private String volumeConcatenation(String firstPath, String secondPath, String outputPath,
                                       double gainValue){
        FFmpegBuilder builder = new FFmpegBuilder()
                .setInput(firstPath)
                .addInput(secondPath)
                .setComplexFilter(String.format(
                        "[0]volume=%.2f,aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo[speech];" +
                                "[1]aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo[song];" +
                                "[speech][song]concat=n=2:v=0:a=1",
                        gainValue))
                .addOutput(outputPath)
                .setAudioCodec("libmp3lame")
                .setAudioSampleRate(SAMPLE_RATE)
                .setAudioChannels(2)
                .done();

        executor.createJob(builder).run();
        return outputPath;
    }

    private double getAudioDuration(String filePath) throws IOException {
        FFmpegProbeResult probeResult = ffprobe.probe(filePath);
        return probeResult.getFormat().duration;
    }
    private void cleanupFiles(String... paths) {
        for (String path : paths) {
            try {
                Files.deleteIfExists(Path.of(path));
            } catch (IOException e) {
                // ignore
            }
        }
    }
}