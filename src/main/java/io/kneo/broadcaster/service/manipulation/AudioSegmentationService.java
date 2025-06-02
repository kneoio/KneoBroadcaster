package io.kneo.broadcaster.service.manipulation;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.model.SegmentInfo;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class AudioSegmentationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioSegmentationService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("HH");
    private final SoundFragmentService soundFragmentService;
    private final FFmpegProvider ffmpeg;

    @Setter
    public static int segmentDuration;
    private final String outputDir;

    @Inject
    public AudioSegmentationService(SoundFragmentService service, BroadcasterConfig broadcasterConfig, FFmpegProvider ffmpeg, HlsPlaylistConfig hlsPlaylistConfig) {
        this.soundFragmentService = service;
        this.ffmpeg = ffmpeg;
        this.outputDir = broadcasterConfig.getSegmentationOutputDir();
        new File(outputDir).mkdirs();
        segmentDuration = hlsPlaylistConfig.getSegmentDuration();
    }

    public Uni<ConcurrentLinkedQueue<HlsSegment>> slice(SoundFragment soundFragment) {
        return soundFragmentService.getFile(
                        soundFragment.getId(),
                        soundFragment.getFileMetadataList().get(0).getSlugName(),
                        SuperUser.build()
                )
                .onItem().transformToUni(metadata -> {
                    ConcurrentLinkedQueue<HlsSegment> oneFragmentSegments = new ConcurrentLinkedQueue<>();
                    List<SegmentInfo> segments = segmentAudioFile(metadata.getFilePath(),
                            soundFragment.getMetadata(),
                            soundFragment.getId());

                    for (SegmentInfo segment : segments) {
                        try {
                            byte[] data = Files.readAllBytes(Paths.get(segment.path()));
                            HlsSegment hlsSegment = new HlsSegment(
                                    0,
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
                    return Uni.createFrom().item(oneFragmentSegments);
                })
                .onFailure().recoverWithUni(throwable -> {
                    LOGGER.error("Failed to process sound fragment", throwable);
                    return Uni.createFrom().failure(throwable);
                });
    }

    public List<SegmentInfo> segmentAudioFile(Path audioFilePath, String songMetadata, UUID fragmentId) {
        List<SegmentInfo> segments = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        String today = now.format(DATE_FORMATTER);
        String currentHour = now.format(HOUR_FORMATTER);
        String sanitizedSongName = sanitizeFileName(songMetadata);

        Path songDir = Paths.get(outputDir, today, currentHour, sanitizedSongName);

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