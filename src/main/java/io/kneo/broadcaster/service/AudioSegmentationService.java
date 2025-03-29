package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Setter;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class AudioSegmentationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioSegmentationService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    FFmpegProvider ffmpeg;

    @Inject
    BroadcasterConfig broadcasterConfig;

    @Setter
    public static int segmentDuration = 20;
    private final String outputDir;

    @Inject
    public AudioSegmentationService(BroadcasterConfig broadcasterConfig, FFmpegProvider ffmpeg) {
        this.broadcasterConfig = broadcasterConfig;
        this.ffmpeg = ffmpeg;
        this.outputDir = broadcasterConfig.getSegmentationOutputDir() != null
                ? broadcasterConfig.getSegmentationOutputDir()
                : Paths.get(System.getProperty("java.io.tmpdir"), "hls-segments").toString();

        initializeOutputDirectory();
    }

    private void initializeOutputDirectory() {
        new File(outputDir).mkdirs();
        cleanupDirectory();
    }

    private void cleanupDirectory() {
        try {
            LOGGER.info("Cleaning up segments directory: {}", outputDir);
            File directory = new File(outputDir);
            cleanRecursively(directory, true);
        } catch (Exception e) {
            LOGGER.error("Error cleaning segments directory", e);
        }
    }

    private void cleanRecursively(File directory, boolean isRoot) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    cleanRecursively(file, false);
                    // Delete empty directories except the root
                    if (file.listFiles() == null || file.listFiles().length == 0) {
                        file.delete();
                    }
                } else {
                    if (file.getName().endsWith(".ts") || file.getName().endsWith(".txt")) {
                        if (file.delete()) {
                            LOGGER.debug("Deleted temporary file: {}", file.getAbsolutePath());
                        } else {
                            LOGGER.warn("Failed to delete temporary file: {}", file.getAbsolutePath());
                        }
                    }
                }
            }
        }
    }

    public ConcurrentLinkedQueue<HlsSegment> slice(SoundFragment soundFragment) {
        ConcurrentLinkedQueue<HlsSegment> oneFragmentSegments = new ConcurrentLinkedQueue<>();
        List<SegmentInfo> segments = segmentAudioFile(soundFragment.getFilePath(), soundFragment.getMetadata(), soundFragment.getId());

        for (SegmentInfo segment : segments) {
            try {
                byte[] data = Files.readAllBytes(Paths.get(segment.path()));
                HlsSegment hlsSegment = new HlsSegment(
                        data,
                        segment.duration(),
                        segment.fragmentId(),
                        segment.metadata(),
                        System.currentTimeMillis() / 1000 + segment.sequenceIndex()
                );

                oneFragmentSegments.add(hlsSegment);

            } catch (Exception e) {
                LOGGER.error("Error adding segment to playlist: {}", segment.path(), e);
            }
        }
        return oneFragmentSegments;
    }

    public List<SegmentInfo> segmentAudioFile(Path audioFilePath, String songMetadata, UUID fragmentId) {
        List<SegmentInfo> segments = new ArrayList<>();
        String today = LocalDate.now().format(DATE_FORMATTER);
        String sanitizedSongName = sanitizeFileName(songMetadata);
        Path songDir = Paths.get(outputDir, today, sanitizedSongName);

        try {
            Files.createDirectories(songDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create directory: {}", songDir, e);
            return segments;
        }

        String baseName = UUID.randomUUID().toString();
        String segmentPattern = songDir + File.separator + baseName + "_%03d.ts";
        String segmentListFile = songDir + File.separator + baseName + "_segments.txt";

        try {
            FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(audioFilePath.toString())
                    .addOutput(segmentPattern)
                    .setAudioCodec("aac")
                    .setAudioBitRate(128000) // 128kbps
                    .setFormat("segment")
                    .addExtraArgs("-segment_time", String.valueOf(segmentDuration))
                    .addExtraArgs("-segment_format", "mpegts")
                    .addExtraArgs("-segment_list", segmentListFile)
                    .addExtraArgs("-segment_list_type", "flat")
                    .done();

            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg.getFFmpeg());
            executor.createJob(builder).run();
            List<String> segmentFiles = Files.readAllLines(Paths.get(segmentListFile));
            for (int i = 0; i < segmentFiles.size(); i++) {
                String segmentFile = segmentFiles.get(i).trim();
                if (!segmentFile.isEmpty()) {
                    Path segmentPath = Paths.get(songDir.toString(), segmentFile);

                    SegmentInfo info = new SegmentInfo(
                            segmentPath.toString(),
                            songMetadata,
                            fragmentId,
                            segmentDuration,
                            i
                    );
                    segments.add(info);
                }
            }

        } catch (IOException e) {
            LOGGER.error("Something wrong with FFMpeg: {}, error: {}", audioFilePath, e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error segmenting audio file: {}", audioFilePath, e);
        }
        return segments;
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .trim();
    }
}