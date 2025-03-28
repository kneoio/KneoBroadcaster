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

@ApplicationScoped
public class AudioSegmentationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioSegmentationService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    BroadcasterConfig broadcasterConfig;

    @Setter
    public static int segmentDuration = 20;
    private final String outputDir;

    @Inject
    public AudioSegmentationService(BroadcasterConfig broadcasterConfig) {
        this.broadcasterConfig = broadcasterConfig;
        this.outputDir = Paths.get(System.getProperty("java.io.tmpdir"), "hls-segments").toString();
        new File(outputDir).mkdirs();
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