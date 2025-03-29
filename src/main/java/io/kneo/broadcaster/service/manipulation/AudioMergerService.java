package io.kneo.broadcaster.service.manipulation;

import io.kneo.broadcaster.config.BroadcasterConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@ApplicationScoped
public class AudioMergerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioMergerService.class);

    private final BroadcasterConfig broadcasterConfig;
    private final String outputDir;

    @Inject
    public AudioMergerService(BroadcasterConfig broadcasterConfig) {
        this.broadcasterConfig = broadcasterConfig;
        // Use the configured path for merged files instead of a temporary directory
        this.outputDir = broadcasterConfig.getPathForMerged();
        new File(outputDir).mkdirs();
    }

    public Path mergeAudioFiles(Path firstFilePath, Path secondFilePath, int silenceDurationSeconds) {
        String mergedFileName = UUID.randomUUID() + ".mp3";
        Path outputFilePath = Paths.get(outputDir, mergedFileName);

        try {
            FFmpeg ffmpeg = new FFmpeg(broadcasterConfig.getFfmpegPath());
            FFmpegBuilder builder;

            if (silenceDurationSeconds > 0) {
                String silenceFileName = "silence_" + UUID.randomUUID() + ".mp3";
                Path silenceFilePath = Paths.get(outputDir, silenceFileName);

                FFmpegBuilder silenceBuilder = new FFmpegBuilder()
                        .addExtraArgs("-f", "lavfi")
                        .setInput("anullsrc=r=44100:cl=stereo:d=" + silenceDurationSeconds)
                        .addOutput(silenceFilePath.toString())
                        .setAudioCodec("libmp3lame")
                        .setAudioBitRate(128000)
                        .done();

                FFmpegExecutor executor = new FFmpegExecutor(ffmpeg);
                executor.createJob(silenceBuilder).run();
                String concatFilePath = Paths.get(outputDir, "concat_" + UUID.randomUUID() + ".mp3").toString();
                Path path = Paths.get(concatFilePath);
                try {
                    java.nio.file.Files.writeString(path,
                            "file '" + firstFilePath + "'\n" +
                                    "file '" + silenceFilePath + "'\n" +
                                    "file '" + silenceFilePath + "'\n");
                } catch (IOException e) {
                    LOGGER.error("Failed to create concat file", e);
                    return null;
                }

                builder = new FFmpegBuilder()
                        .setInput(concatFilePath)
                        .addExtraArgs("-f", "concat")
                        .addExtraArgs("-safe", "0")
                        .addOutput(outputFilePath.toString())
                        .setAudioCodec("libmp3lame")
                        .setAudioBitRate(128000)
                        .done();

                executor.createJob(builder).run();

                try {
                    java.nio.file.Files.deleteIfExists(silenceFilePath);
                    java.nio.file.Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.warn("Could not delete temporary files", e);
                }
            } else {
                String concatFilePath = Paths.get(outputDir, "concat_" + UUID.randomUUID() + ".mp3").toString();
                Path path = Paths.get(concatFilePath);
                try {
                    java.nio.file.Files.writeString(path,
                            "file '" + firstFilePath + "'\n" +
                                    "file '" + secondFilePath + "'\n");
                } catch (IOException e) {
                    LOGGER.error("Failed to create concat file", e);
                    return null;
                }

                builder = new FFmpegBuilder()
                        .setInput(concatFilePath)
                        .addExtraArgs("-f", "concat")
                        .addExtraArgs("-safe", "0")
                        .addOutput(outputFilePath.toString())
                        .setAudioCodec("libmp3lame")
                        .setAudioBitRate(128000)
                        .done();

                FFmpegExecutor executor = new FFmpegExecutor(ffmpeg);
                executor.createJob(builder).run();

                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.warn("Could not delete concat file", e);
                }
            }

            return outputFilePath;
        } catch (Exception e) {
            LOGGER.error("Error merging audio files: {} and {}", firstFilePath, secondFilePath, e);
            return null;
        }
    }
}