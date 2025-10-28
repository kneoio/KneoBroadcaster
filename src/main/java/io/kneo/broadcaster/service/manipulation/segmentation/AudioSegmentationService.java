package io.kneo.broadcaster.service.manipulation.segmentation;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.SegmentInfo;
import io.kneo.broadcaster.model.live.SongMetadata;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.stream.HlsSegment;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class AudioSegmentationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioSegmentationService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("HH");
    private final FFmpegProvider ffmpeg;
    private final String outputDir;
    private final int segmentDuration;

    @Inject
    public AudioSegmentationService(BroadcasterConfig broadcasterConfig, FFmpegProvider ffmpeg, HlsPlaylistConfig hlsPlaylistConfig) {
        this.ffmpeg = ffmpeg;
        this.outputDir = broadcasterConfig.getSegmentationOutputDir();
        this.segmentDuration = hlsPlaylistConfig.getSegmentDuration();
        new File(outputDir).mkdirs();
        preallocateDirectories();
    }

    public Uni<Map<Long, ConcurrentLinkedQueue<HlsSegment>>> slice(SongMetadata songMetadata, Path filePath, List<Long> bitRates) {
        return Uni.createFrom().item(() -> segmentAudioFileMultipleBitrates(filePath, songMetadata, bitRates))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(e -> LOGGER.error("Failed to slice audio file: {}", filePath, e))
                .chain(this::createHlsQueueFromMultipleBitrateSegments);
    }

    private Uni<Map<Long, ConcurrentLinkedQueue<HlsSegment>>> createHlsQueueFromMultipleBitrateSegments(
            Map<Long, List<SegmentInfo>> segmentsByBitrate) {
        return Uni.createFrom().item(() -> {
            Map<Long, ConcurrentLinkedQueue<HlsSegment>> resultMap = new ConcurrentHashMap<>();
            List<Uni<Void>> tasks = segmentsByBitrate.entrySet().stream()
                    .map(entry -> Uni.createFrom().item(() -> {
                        ConcurrentLinkedQueue<HlsSegment> segments = createHlsQueueFromSegments(entry.getValue());
                        resultMap.put(entry.getKey(), segments);
                        return (Void) null;
                    }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                    .toList();
            Uni.combine().all().unis(tasks).combinedWith(list -> (Void) null).await().indefinitely();
            return resultMap;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private ConcurrentLinkedQueue<HlsSegment> createHlsQueueFromSegments(List<SegmentInfo> segments) {
        ConcurrentLinkedQueue<HlsSegment> hlsSegments = new ConcurrentLinkedQueue<>();
        for (SegmentInfo segment : segments) {
            try {
                byte[] data = Files.readAllBytes(Paths.get(segment.path()));
                HlsSegment hlsSegment = new HlsSegment(
                        0,
                        data,
                        segment.duration(),
                        segment.songMetadata(),
                        System.currentTimeMillis() / 1000 + segment.sequenceIndex()
                );
                hlsSegments.add(hlsSegment);
            } catch (IOException e) {
                LOGGER.error("Error reading segment file into byte array: {}", segment.path(), e);
            }
        }
        return hlsSegments;
    }

    public Map<Long, List<SegmentInfo>> segmentAudioFileMultipleBitrates(Path audioFilePath, SongMetadata songMetadata, List<Long> bitRates) {
        Map<Long, List<SegmentInfo>> segmentsByBitrate = new ConcurrentHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        String today = now.format(DATE_FORMATTER);
        String currentHour = now.format(HOUR_FORMATTER);
        String sanitizedSongName = sanitizeFileName(songMetadata.toString());

        try {
            FFmpegBuilder builder = new FFmpegBuilder().setInput(audioFilePath.toString());
            Map<Long, BitrateOutputInfo> outputInfoMap = new HashMap<>();

            for (Long bitRate : bitRates) {
                String bitrateDir = sanitizedSongName + "_" + bitRate + "k";
                Path songDir = Paths.get(outputDir, today, currentHour, bitrateDir);
                Files.createDirectories(songDir);
                String baseName = UUID.randomUUID().toString();
                String segmentPattern = songDir + File.separator + baseName + "_%03d.ts";
                String segmentListFile = songDir + File.separator + baseName + "_segments.txt";
                outputInfoMap.put(bitRate, new BitrateOutputInfo(songDir, segmentListFile, songMetadata));

                builder.addOutput(segmentPattern)
                        .setAudioCodec("aac")
                        .setAudioBitRate(bitRate)
                        .setFormat("segment")
                        .addExtraArgs("-segment_time", String.valueOf(segmentDuration))
                        .addExtraArgs("-segment_format", "mpegts")
                        .addExtraArgs("-segment_list", segmentListFile)
                        .addExtraArgs("-segment_list_type", "flat")
                        .addExtraArgs("-ac", "2")
                        .addExtraArgs("-ar", "44100")
                        .addExtraArgs("-channel_layout", "stereo")
                        .addExtraArgs("-map", "0:a")
                        .addExtraArgs("-metadata", "title=" + songMetadata.getTitle())
                        .addExtraArgs("-metadata", "artist=" + songMetadata.getArtist())
                        .addExtraArgs("-af", "dynaudnorm,acompressor")
                        .addExtraArgs("-threads", "0")
                        .addExtraArgs("-preset", "ultrafast")
                        .addExtraArgs("-aac_coder", "twoloop")
                        .addExtraArgs("-nostdin")
                        .addExtraArgs("-vn")
                        .done();
            }

            long usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);  //to stat
            int activeThreads = Thread.activeCount();
            LOGGER.warn("Pre-FFmpeg spawn: usedMem={}MB, activeThreads={}, ffmpegBin={}, file={}",
                    usedMem, activeThreads, ffmpeg.getFFmpeg().getPath(), audioFilePath);

            try {
                if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
                    try {
                        String procs = new String(Runtime.getRuntime().exec("ps -e | wc -l").getInputStream().readAllBytes()).trim();
                        LOGGER.warn("System processes count before spawn: {}", procs);
                    } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}

            LOGGER.info("FFmpeg multi-bitrate segmentation command: {}", builder.toString());
            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg.getFFmpeg());
            executor.createJob(builder).run();

            long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);  // to stat
            LOGGER.warn("Post-FFmpeg spawn: freeMem={}MB, threadsNow={}", freeMem,
                    java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount());

            Map<Long, List<SegmentInfo>> processedSegments = new ConcurrentHashMap<>();
            List<Uni<Void>> segmentTasks = outputInfoMap.entrySet().stream()
                    .map(entry -> Uni.createFrom().item(() -> {
                        List<SegmentInfo> segments = processSegmentList(entry.getKey(), entry.getValue());
                        processedSegments.put(entry.getKey(), segments);
                        return (Void) null;
                    }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                    .toList();
            Uni.combine().all().unis(segmentTasks).combinedWith(list -> (Void) null).await().indefinitely();
            segmentsByBitrate.putAll(processedSegments);

        } catch (IOException e) {
            LOGGER.error("FFmpeg error for file: {}, error: {}", audioFilePath, e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error segmenting audio file: {}", audioFilePath, e);
        }
        return segmentsByBitrate;
    }

    private List<SegmentInfo> processSegmentList(Long bitRate, BitrateOutputInfo outputInfo) {
        List<SegmentInfo> segments = new ArrayList<>();
        try {
            List<String> segmentFiles = Files.readAllLines(Paths.get(outputInfo.segmentListFile));
            if (!segmentFiles.isEmpty()) {
                String firstSegment = segmentFiles.get(0).trim();
                Path firstSegmentPath = Paths.get(outputInfo.songDir.toString(), firstSegment);
                LOGGER.info("Debugging first segment for {}k bitrate: {}", bitRate, firstSegmentPath);
            }
            for (int i = 0; i < segmentFiles.size(); i++) {
                String segmentFile = segmentFiles.get(i).trim();
                if (!segmentFile.isEmpty()) {
                    Path segmentPath = Paths.get(outputInfo.songDir.toString(), segmentFile);
                    SegmentInfo info = new SegmentInfo(
                            segmentPath.toString(),
                            outputInfo.songMetadata,
                            segmentDuration,
                            i
                    );
                    segments.add(info);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error reading segment list file: {}", outputInfo.segmentListFile, e);
        }
        return segments;
    }

    private void preallocateDirectories() {
        LocalDateTime now = LocalDateTime.now();
        String today = now.format(DATE_FORMATTER);
        for (int hour = 0; hour < 24; hour++) {
            Path hourDir = Paths.get(outputDir, today, String.format("%02d", hour));
            try {
                Files.createDirectories(hourDir);
            } catch (IOException e) {
                LOGGER.warn("Could not pre-create directory: {}", hourDir);
            }
        }
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .trim();
    }

    private record BitrateOutputInfo(Path songDir, String segmentListFile, SongMetadata songMetadata) {
    }
}
