package io.kneo.broadcaster.service.manipulation;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.FileMetadata;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    public Uni<Path> mergeAudioFiles(Path speechFilePath, FileMetadata songFileMetadata, int silenceDurationSeconds, RadioStation radioStation) {
        String mergedFileName = UUID.randomUUID() + "." + config.getAudioOutputFormat();
        Path outputFilePath = Paths.get(outputDir, mergedFileName);

        return aiAgentService.getById(radioStation.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(aiAgent -> {
                    double gainValue = aiAgent.getMerger().getGainIntro();

                    return createTempFileFromMetadata(songFileMetadata)
                            .chain(songTempFile -> {
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
                                                                    .addInput(songTempFile.toString())
                                                                    .addOutput(outputFilePath.toString())
                                                                    .addExtraArgs("-filter_complex",
                                                                            String.format("[0]volume=%.2f[speech];[speech][1][2]concat=n=3:v=0:a=1", gainValue))
                                                                    .done()
                                                    ).onTermination().invoke(() -> {
                                                                cleanupFile(silenceFilePath);
                                                                cleanupFile(songTempFile);
                                                            })
                                                            .onItem().transform(v -> {
                                                                LOGGER.info("Successfully merged audio files to: {} with speech gain: {}", outputFilePath, gainValue);
                                                                return outputFilePath;
                                                            })
                                            );
                                } else {
                                    return executeFFmpegAsync(
                                            new FFmpegBuilder()
                                                    .setInput(speechFilePath.toString())
                                                    .addInput(songTempFile.toString())
                                                    .addOutput(outputFilePath.toString())
                                                    .addExtraArgs("-filter_complex",
                                                            String.format("[0]volume=%.2f[speech];[speech][1]concat=n=2:v=0:a=1", gainValue))
                                                    .done()
                                    ).onTermination().invoke(() -> cleanupFile(songTempFile))
                                            .onItem().transform(v -> {
                                                LOGGER.info("Successfully merged audio files to: {} with speech gain: {}", outputFilePath, gainValue);
                                                return outputFilePath;
                                            });
                                }
                            });
                })
                .onFailure().transform(failure -> {
                    LOGGER.error("Error merging audio files: speech={}, song={}, silence={}s",
                            speechFilePath, songFileMetadata != null ? songFileMetadata.getFileKey() : "null", silenceDurationSeconds, failure);
                    if (failure instanceof AudioMergeException) {
                        return failure;
                    }
                    return new AudioMergeException("Failed to merge audio files", failure);
                });
    }

    private Uni<Path> createTempFileFromMetadata(FileMetadata fileMetadata) {
        return Uni.createFrom().item(() -> {
                    try {
                        String extension = getFileExtension(fileMetadata.getMimeType());
                        Path tempFile = Files.createTempFile("temp_song_", extension);

                        LOGGER.debug("Creating temporary song file: {}", tempFile);

                        try (InputStream stream = fileMetadata.getInputStream();
                             FileOutputStream outputStream = new FileOutputStream(tempFile.toFile())) {

                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            long totalBytes = 0;

                            while ((bytesRead = stream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                                totalBytes += bytesRead;
                            }

                            LOGGER.debug("Temporary song file created successfully: {} bytes", totalBytes);
                        }

                        return tempFile;
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to create temporary file from metadata", e);
                    }
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().transform(failure -> {
                    if (failure instanceof RuntimeException && failure.getCause() instanceof IOException) {
                        return new AudioMergeException("Failed to create temporary file", failure.getCause());
                    }
                    return new AudioMergeException("Failed to create temporary file", failure);
                });
    }

    private String getFileExtension(String mimeType) {
        if (mimeType == null) return ".tmp";

        return switch (mimeType.toLowerCase()) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/wave" -> ".wav";
            case "audio/flac" -> ".flac";
            case "audio/aac" -> ".aac";
            case "audio/ogg" -> ".ogg";
            case "audio/m4a" -> ".m4a";
            default -> ".tmp";
        };
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