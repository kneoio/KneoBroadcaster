package io.kneo.broadcaster.service.manipulation;

import io.kneo.broadcaster.config.BroadcasterConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AudioMergerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioMergerService.class);

    private final String outputDir;
    private final FFmpegProvider ffmpeg;

    @Inject
    public AudioMergerService(BroadcasterConfig broadcasterConfig, FFmpegProvider ffmpeg) {
        this.ffmpeg = ffmpeg;
        this.outputDir = broadcasterConfig.getPathForMerged();
        initializeOutputDirectory();
    }

    private void initializeOutputDirectory() {
        new File(outputDir).mkdirs();
        cleanupTempFiles();
    }

    private void cleanupTempFiles() {
        try {
            File directory = new File(outputDir);
            File[] files = directory.listFiles((dir, name) -> name.startsWith("silence_"));
            if (files != null) {
                for (File file : files) {
                    Files.deleteIfExists(file.toPath());
                    LOGGER.debug("Deleted temporary file: {}", file.getName());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Error cleaning up temporary files", e);
        }
    }

    public Path mergeAudioFiles(Path speachFilePath, Path songFilePath, int silenceDurationSeconds) {
        String mergedFileName = UUID.randomUUID() + ".mp3";
        Path outputFilePath = Paths.get(outputDir, mergedFileName);
        Path silenceFilePath = null;

        try {
            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg.getFFmpeg());

            if (silenceDurationSeconds > 0) {
                String silenceFileName = "silence_" + UUID.randomUUID() + ".mp3";
                silenceFilePath = Paths.get(outputDir, silenceFileName);

                executor.createJob(
                        new FFmpegBuilder()
                                .setInput("anullsrc=r=44100:cl=stereo")
                                .addOutput(silenceFilePath.toString())
                                .setDuration(silenceDurationSeconds, TimeUnit.SECONDS)
                                .done()
                ).run();

                executor.createJob(
                        new FFmpegBuilder()
                                .setInput(speachFilePath.toString())
                                .addInput(silenceFilePath.toString())
                                .addInput(songFilePath.toString())
                                .addOutput(outputFilePath.toString())
                                .addExtraArgs("-filter_complex", "concat=n=3:v=0:a=1")
                                .done()
                ).run();
            } else {
                executor.createJob(
                        new FFmpegBuilder()
                                .setInput(speachFilePath.toString())
                                .addInput(songFilePath.toString())
                                .addOutput(outputFilePath.toString())
                                .addExtraArgs("-filter_complex", "concat=n=2:v=0:a=1")
                                .done()
                ).run();
            }

            cleanupFile(silenceFilePath);

            return outputFilePath;
        } catch (Exception e) {
            LOGGER.error("Error merging files", e);
            cleanupFile(silenceFilePath);
            return null;
        }
    }

    private void cleanupFile(Path filePath) {
        if (filePath != null) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException ex) {
                LOGGER.warn("Failed to delete temporary file: {}", filePath, ex);
            }
        }
    }
}