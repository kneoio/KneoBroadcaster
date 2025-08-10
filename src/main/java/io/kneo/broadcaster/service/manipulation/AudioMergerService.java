package io.kneo.broadcaster.service.manipulation;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
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
    private final BroadcasterConfig config;
    private final FFmpegExecutor executor;
    private final AiAgentService aiAgentService;

    @Inject
    public AudioMergerService(BroadcasterConfig broadcasterConfig, FFmpegProvider ffmpeg, AiAgentService aiAgentService) throws AudioMergeException {
        this.config = broadcasterConfig;
        this.outputDir = broadcasterConfig.getPathForMerged();
        this.aiAgentService = aiAgentService;

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

    public Uni<Path> mergeAudioFiles(Path speechFilePath, Path songFilePath, int silenceDurationSeconds, RadioStation radioStation) {
        String mergedFileName = UUID.randomUUID() + "." + config.getAudioOutputFormat();
        Path outputFilePath = Paths.get(outputDir, mergedFileName);

        return aiAgentService.getById(radioStation.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(aiAgent -> {
                    //double gainValue = aiAgent.getMerger().getGainIntro();
                    double gainValue = 2;

                    if (silenceDurationSeconds > 0) {
                        if (silenceDurationSeconds > config.getMaxSilenceDuration()) {
                            return Uni.createFrom().failure(
                                    new AudioMergeException("Silence duration too long (max " +
                                            config.getMaxSilenceDuration() + " seconds): " + silenceDurationSeconds)
                            );
                        }

                        return createSilenceFileAsync(silenceDurationSeconds)
                                .chain(silenceFilePath ->
                                        executeFFmpegAsync(
                                                new FFmpegBuilder()
                                                        .setInput(speechFilePath.toString())
                                                        .addInput(silenceFilePath.toString())
                                                        .addInput(songFilePath.toString())
                                                        .addOutput(outputFilePath.toString())
                                                        .addExtraArgs("-filter_complex",
                                                                String.format("[0]volume=%.2f[speech];[speech][1][2]concat=n=3:v=0:a=1", gainValue))
                                                        .done()
                                        ).onTermination().invoke(() -> cleanupFile(silenceFilePath))
                                                .onItem().transform(v -> {
                                                    LOGGER.info("Successfully merged audio files to: {} with speech gain: {}", outputFilePath, gainValue);
                                                    return outputFilePath;
                                                })
                                );
                    } else {
                        return executeFFmpegAsync(
                                new FFmpegBuilder()
                                        .setInput(speechFilePath.toString())
                                        .addInput(songFilePath.toString())
                                        .addOutput(outputFilePath.toString())
                                        .addExtraArgs("-filter_complex",
                                                String.format("[0]volume=%.2f[speech];[speech][1]concat=n=2:v=0:a=1", gainValue))
                                        .done()
                        ).onItem().transform(v -> {
                            LOGGER.info("Successfully merged audio files to: {} with speech gain: {}", outputFilePath, gainValue);
                            return outputFilePath;
                        });
                    }
                })
                .onFailure().transform(failure -> {
                    LOGGER.error("Error merging audio files: speech={}, song={}, silence={}s",
                            speechFilePath, songFilePath, silenceDurationSeconds, failure);
                    if (failure instanceof AudioMergeException) {
                        return failure;
                    }
                    return new AudioMergeException("Failed to merge audio files", failure);
                });
    }

    private Uni<Path> createSilenceFileAsync(int silenceDurationSeconds) {
        return Uni.createFrom().item(() -> {
                    try {
                        return createSilenceFile(silenceDurationSeconds);
                    } catch (AudioMergeException e) {
                        throw new RuntimeException(e);
                    }
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().transform(failure -> {
                    if (failure instanceof RuntimeException && failure.getCause() instanceof AudioMergeException) {
                        return (AudioMergeException) failure.getCause();
                    }
                    return new AudioMergeException("Failed to create silence file", failure);
                });
    }

    private Uni<Void> executeFFmpegAsync(FFmpegBuilder builder) {
        return Uni.createFrom().item(() -> {
                    executor.createJob(builder).run();
                    return (Void) null;
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().transform(failure ->
                        new AudioMergeException("FFmpeg execution failed", failure)
                );
    }

    private Path createSilenceFile(int silenceDurationSeconds) throws AudioMergeException {
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