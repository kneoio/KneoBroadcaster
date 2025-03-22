package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.model.SoundFragment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Setter;
import net.bramp.ffmpeg.FFmpeg;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AudioSegmentationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioSegmentationService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    BroadcasterConfig broadcasterConfig;

    @Setter
    public static int segmentDuration = 20;
    private final String outputDir;
    private final ScheduledExecutorService scheduler;
    @Setter
    private int retentionDays = 1;
    @Setter
    private int cleanupIntervalHours = 24;


    @Inject
    public AudioSegmentationService(BroadcasterConfig broadcasterConfig) {
        this.broadcasterConfig = broadcasterConfig;
        this.outputDir = Paths.get(System.getProperty("java.io.tmpdir"), "hls-segments").toString();
        new File(outputDir).mkdirs();
        this.scheduler = Executors.newScheduledThreadPool(1);

        startCleanupScheduler();

        LOGGER.info("Initialized AudioSegmentationService with outputDir: {}, cleanup every {} hours, ffmpegPath: {}",
                outputDir, cleanupIntervalHours, broadcasterConfig.getFfmpegPath());
    }

    private void startCleanupScheduler() {
        scheduler.schedule(this::cleanupOldSegments, 10, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(
                this::cleanupOldSegments,
                cleanupIntervalHours,
                cleanupIntervalHours,
                TimeUnit.HOURS);
    }


    public ConcurrentLinkedQueue<HlsSegment> slice(SoundFragment soundFragment) {
        ConcurrentLinkedQueue<HlsSegment> oneFragmentSegments = new ConcurrentLinkedQueue<>();
        List<SegmentInfo> segments = segmentAudioFile(soundFragment.getFilePath(), soundFragment.getMetadata(), soundFragment.getId());

        for (SegmentInfo segment : segments) {
            try {
                byte[] data = Files.readAllBytes(Paths.get(segment.path()));

                // Create a unique segment URI based on the fragment ID and sequence
                String uniquePrefix = soundFragment.getId().toString().substring(0, 8); // Use first part of UUID
                String segmentUri = "segments/" + uniquePrefix + "_" + (System.currentTimeMillis() / 1000 + segment.sequenceIndex()) + ".ts";

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
            FFmpeg ffmpeg = new FFmpeg(broadcasterConfig.getFfmpegPath());
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

            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg);
            executor.createJob(builder).run();

            // Read the segment list file to get all segments
            List<String> segmentFiles = Files.readAllLines(Paths.get(segmentListFile));

            // Create segment info objects
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


    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .trim();
    }

    private void cleanupOldSegments() {
        try {
            LOGGER.info("Starting scheduled cleanup of segment files...");
            LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);
            String cutoffDateStr = cutoffDate.format(DATE_FORMATTER);
            File[] dateDirs = new File(outputDir).listFiles(File::isDirectory);
            if (dateDirs == null) {
                LOGGER.warn("No date directories found in {}", outputDir);
                return;
            }

            int deletedFolders = 0;
            int deletedFiles = 0;

            // Check each date directory
            for (File dateDir : dateDirs) {
                String dirName = dateDir.getName();

                // If directory date is before cutoff date, delete it
                if (dirName.compareTo(cutoffDateStr) < 0) {
                    deletedFiles += deleteDirectory(dateDir);
                    deletedFolders++;
                }
            }

            LOGGER.info("Cleanup complete: removed {} directories and {} files",
                    deletedFolders, deletedFiles);

        } catch (Exception e) {
            LOGGER.error("Error during scheduled cleanup", e);
        }
    }

    private int deleteDirectory(File directory) {
        int count = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    count += deleteDirectory(file);
                } else {
                    if (file.delete()) {
                        count++;
                    } else {
                        LOGGER.warn("Failed to delete file: {}", file.getAbsolutePath());
                    }
                }
            }
        }

        if (directory.delete()) {
            LOGGER.debug("Deleted directory: {}", directory.getAbsolutePath());
        } else {
            LOGGER.warn("Failed to delete directory: {}", directory.getAbsolutePath());
        }

        return count;
    }
}