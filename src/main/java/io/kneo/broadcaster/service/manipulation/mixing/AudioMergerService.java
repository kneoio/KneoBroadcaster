package io.kneo.broadcaster.service.manipulation.mixing;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
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

@ApplicationScoped
public class AudioMergerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioMergerService.class);

    private final String outputDir;
    private final String tempBaseDir;
    private final BroadcasterConfig config;
    private final FFmpegExecutor executor;
    private final AiAgentService aiAgentService;

    @Inject
    public AudioMergerService(BroadcasterConfig broadcasterConfig, FFmpegProvider ffmpeg, AiAgentService aiAgentService) throws AudioMergeException {
        this.config = broadcasterConfig;
        this.outputDir = broadcasterConfig.getPathForMerged();
        this.tempBaseDir = broadcasterConfig.getPathUploads() + "/audio-processing";
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

    public Uni<Path> mergeAudioFiles(Path speechFilePath, FileMetadata songFileMetadata, RadioStation radioStation) {
        String mergedFileName = UUID.randomUUID() + "." + config.getAudioOutputFormat();
        Path outputFilePath = Paths.get(outputDir, mergedFileName);

        return aiAgentService.getById(radioStation.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(aiAgent -> {
                    double gainValue = aiAgent.getMerger().getGainIntro();

                    return songFileMetadata.materializeFileStream(tempBaseDir)
                            .chain(songTempFile -> {
                                return executeFFmpegAsync(
                                        new FFmpegBuilder()
                                                .setInput(speechFilePath.toString())
                                                .addInput(songTempFile.toString())
                                                .addOutput(outputFilePath.toString())
                                                .addExtraArgs("-filter_complex",
                                                        String.format("[0]volume=%.2f[speech];[speech][1]concat=n=2:v=0:a=1", gainValue))
                                                .done()
                                ).onItem().transform(v -> {
                                    LOGGER.info("Successfully merged audio files to: {} with speech gain: {}", outputFilePath, gainValue);
                                    return outputFilePath;
                                });
                            });
                })
                .onFailure().transform(failure -> {
                    LOGGER.error("Error merging audio files: speech={}, song={}",
                            speechFilePath, songFileMetadata != null ? songFileMetadata.getFileKey() : "null", failure);
                    if (failure instanceof AudioMergeException) {
                        return failure;
                    }
                    return new AudioMergeException("Failed to merge audio files", failure);
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
}