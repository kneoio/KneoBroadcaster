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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@ApplicationScoped
public class AudioMergerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioMergerService.class);

    private final String outputDir;
    private final BroadcasterConfig config;
    private final FFmpegExecutor executor;

    public static class AudioMergeException extends Exception {
        public AudioMergeException(String message, Throwable cause) {
            super(message, cause);
        }

        public AudioMergeException(String message) {
            super(message);
        }
    }

    @Inject
    public AudioMergerService(BroadcasterConfig broadcasterConfig, FFmpegProvider ffmpeg) throws AudioMergeException {
        this.config = broadcasterConfig;
        this.outputDir = broadcasterConfig.getPathForMerged();

        try {
            this.executor = new FFmpegExecutor(ffmpeg.getFFmpeg());
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
            File[] files = directory.listFiles((dir, name) -> name.startsWith("silence_"));
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


    public Path mergeAudioFiles(Path speechFilePath, Path songFilePath, int silenceDurationSeconds) throws AudioMergeException {
        String mergedFileName = UUID.randomUUID() + "." + config.getAudioOutputFormat();
        Path outputFilePath = Paths.get(outputDir, mergedFileName);
        Path silenceFilePath = null;

        try {
            if (silenceDurationSeconds > 0) {
                if (silenceDurationSeconds > config.getMaxSilenceDuration()) {
                    throw new AudioMergeException("Silence duration too long (max " + config.getMaxSilenceDuration() + " seconds): " + silenceDurationSeconds);
                }
                silenceFilePath = createSilenceFile(executor, silenceDurationSeconds);

                executor.createJob(
                        new FFmpegBuilder()
                                .setInput(speechFilePath.toString())
                                .addInput(silenceFilePath.toString())
                                .addInput(songFilePath.toString())
                                .addOutput(outputFilePath.toString())
                                .addExtraArgs("-filter_complex", "concat=n=3:v=0:a=1")
                                .done()
                ).run();
            } else {
                executor.createJob(
                        new FFmpegBuilder()
                                .setInput(speechFilePath.toString())
                                .addInput(songFilePath.toString())
                                .addOutput(outputFilePath.toString())
                                .addExtraArgs("-filter_complex", "concat=n=2:v=0:a=1")
                                .done()
                ).run();
            }

            LOGGER.info("Successfully merged audio files to: {}", outputFilePath);


            //  Path debugDir = Paths.get(System.getProperty("java.io.tmpdir"), "audio_debug");
            //  Files.createDirectories(debugDir);
            //  Path debugFile = debugDir.resolve("debug_" + System.currentTimeMillis() + "_" + outputFilePath.getFileName());
            //  Files.copy(outputFilePath, debugFile, StandardCopyOption.REPLACE_EXISTING);
            //  LOGGER.info("Debug copy created at: {}", debugFile);

            return outputFilePath;

        } catch (Exception e) {
            LOGGER.error("Error merging audio files: speech={}, song={}, silence={}s",
                    speechFilePath, songFilePath, silenceDurationSeconds, e);
            throw new AudioMergeException("Failed to merge audio files", e);
        } finally {
            cleanupFile(silenceFilePath);
        }
    }

    public CompletableFuture<Path> mergeAudioFilesAsync(Path speechFilePath, Path songFilePath,
                                                        int silenceDurationSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mergeAudioFiles(speechFilePath, songFilePath, silenceDurationSeconds);
            } catch (AudioMergeException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Path> mergeAudioFilesAsync(Path speechFilePath, Path songFilePath,
                                                        int silenceDurationSeconds,
                                                        Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                progressCallback.accept("Starting audio merge process...");

                String mergedFileName = UUID.randomUUID() + "." + config.getAudioOutputFormat();
                Path outputFilePath = Paths.get(outputDir, mergedFileName);
                Path silenceFilePath = null;

                try {
                    if (silenceDurationSeconds > 0) {
                        if (silenceDurationSeconds > config.getMaxSilenceDuration()) {
                            throw new AudioMergeException("Silence duration too long (max " + config.getMaxSilenceDuration() + " seconds): " + silenceDurationSeconds);
                        }
                        progressCallback.accept("Creating silence track...");
                        silenceFilePath = createSilenceFile(executor, silenceDurationSeconds);

                        progressCallback.accept("Merging speech, silence, and song...");
                        executor.createJob(
                                new FFmpegBuilder()
                                        .setInput(speechFilePath.toString())
                                        .addInput(silenceFilePath.toString())
                                        .addInput(songFilePath.toString())
                                        .addOutput(outputFilePath.toString())
                                        .addExtraArgs("-filter_complex", "concat=n=3:v=0:a=1")
                                        .done()
                        ).run();
                    } else {
                        progressCallback.accept("Merging speech and song...");
                        executor.createJob(
                                new FFmpegBuilder()
                                        .setInput(speechFilePath.toString())
                                        .addInput(songFilePath.toString())
                                        .addOutput(outputFilePath.toString())
                                        .addExtraArgs("-filter_complex", "concat=n=2:v=0:a=1")
                                        .done()
                        ).run();
                    }

                    progressCallback.accept("Audio merge completed successfully");
                    LOGGER.info("Successfully merged audio files to: {}", outputFilePath);
                    return outputFilePath;

                } finally {
                    cleanupFile(silenceFilePath);
                }

            } catch (AudioMergeException e) {
                progressCallback.accept("Error: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    private Path createSilenceFile(FFmpegExecutor executor, int silenceDurationSeconds) throws AudioMergeException {
        String silenceFileName = "silence_" + UUID.randomUUID() + "." + config.getAudioOutputFormat();
        Path silenceFilePath = Paths.get(outputDir, silenceFileName);

        String silenceSource = String.format("anullsrc=r=%d:cl=%s",
                config.getAudioSampleRate(),
                config.getAudioChannels());

        try {
            executor.createJob(
                    new FFmpegBuilder()
                            .setInput(silenceSource)
                            .addOutput(silenceFilePath.toString())
                            .setDuration(silenceDurationSeconds, TimeUnit.SECONDS)
                            .done()
            ).run();

            return silenceFilePath;
        } catch (Exception e) {
            throw new AudioMergeException("Failed to create silence file", e);
        }
    }

    private void cleanupFile(Path filePath) {
        if (filePath != null) {
            try {
                Files.deleteIfExists(filePath);
                LOGGER.debug("Cleaned up temporary file: {}", filePath);
            } catch (IOException ex) {
                LOGGER.warn("Failed to delete temporary file: {}", filePath, ex);
            }
        }
    }
}