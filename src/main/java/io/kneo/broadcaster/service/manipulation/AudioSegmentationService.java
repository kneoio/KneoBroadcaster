package io.kneo.broadcaster.service.manipulation;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.SegmentInfo;
import io.kneo.broadcaster.model.SoundFragment;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private final FFmpegProvider ffmpeg;

    private final int segmentDuration;
    private final String outputDir;

    @Inject
    public AudioSegmentationService(BroadcasterConfig broadcasterConfig, FFmpegProvider ffmpeg, HlsPlaylistConfig hlsPlaylistConfig) {
        this.ffmpeg = ffmpeg;
        this.outputDir = broadcasterConfig.getSegmentationOutputDir();
        this.segmentDuration = hlsPlaylistConfig.getSegmentDuration();
        new File(outputDir).mkdirs();
    }

    public Uni<ConcurrentLinkedQueue<HlsSegment>> slice(SoundFragment soundFragment, FileMetadata fileMetadata) {
        return Uni.createFrom().item(() -> {
                    Path tempFile = null;
                    try {
                        tempFile = createTempFileFromStream(fileMetadata.getInputStream(), fileMetadata.getMimeType());
                        return segmentAudioFile(tempFile, soundFragment.getTitle(), soundFragment.getArtist(), soundFragment.getId());
                    } catch (IOException e) {
                        LOGGER.error("Failed to create temporary file from stream for fragment: {}", soundFragment.getId(), e);
                        throw new RuntimeException("Failed to process audio stream", e);
                    } finally {
                        if (tempFile != null) {
                            try {
                                Files.deleteIfExists(tempFile);
                                LOGGER.debug("Cleaned up temporary file: {}", tempFile);
                            } catch (IOException e) {
                                LOGGER.warn("Failed to cleanup temporary file: {}", tempFile, e);
                            }
                        }
                    }
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(e -> LOGGER.error("Failed to slice audio file for fragment: {}", soundFragment.getId(), e))
                .chain(this::createHlsQueueFromSegments);
    }

    private Path createTempFileFromStream(InputStream inputStream, String mimeType) throws IOException {
        String extension = getFileExtension(mimeType);
        Path tempFile = Files.createTempFile("audio_segment_", extension);

        LOGGER.debug("Creating temporary file: {}", tempFile);

        try (InputStream stream = inputStream;
             FileOutputStream outputStream = new FileOutputStream(tempFile.toFile())) {

            // Check if stream is still open
          /*  if (!stream.markSupported()) {
                // Add mark support if needed
                stream = new java.io.BufferedInputStream(stream);
            }*/

            // Stream efficiently with buffer
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = stream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            if (totalBytes == 0) {
                throw new IOException("No data read from stream - stream may be closed or empty");
            }

            LOGGER.debug("Temporary file created successfully: {} bytes", totalBytes);
        } catch (IOException e) {
            // Clean up temp file if creation failed
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException deleteEx) {
                LOGGER.warn("Failed to cleanup temp file after stream error: {}", tempFile, deleteEx);
            }
            throw new IOException("Failed to create temp file from stream: " + e.getMessage(), e);
        }

        return tempFile;
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

    private Uni<ConcurrentLinkedQueue<HlsSegment>> createHlsQueueFromSegments(List<SegmentInfo> segments) {
        return Uni.createFrom().item(() -> {
            ConcurrentLinkedQueue<HlsSegment> hlsSegments = new ConcurrentLinkedQueue<>();
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
                    hlsSegments.add(hlsSegment);
                } catch (IOException e) {
                    LOGGER.error("Error reading segment file into byte array: {}", segment.path(), e);
                }
            }
            return hlsSegments;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private void debugAudioProperties(Path audioFilePath) {
        try {
            FFmpegBuilder probeBuilder = new FFmpegBuilder()
                    .setInput(audioFilePath.toString())
                    .addOutput("/dev/null")
                    .addExtraArgs("-f", "null")
                    .done();

            LOGGER.info("Probing audio file properties for: {}", audioFilePath);
            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg.getFFmpeg());
            executor.createJob(probeBuilder).run();
        } catch (Exception e) {
            LOGGER.error("Failed to probe audio file: {}", audioFilePath, e);
        }
    }

    public List<SegmentInfo> segmentAudioFile(Path audioFilePath, String songTitle, String songArtist, UUID fragmentId) {
        List<SegmentInfo> segments = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        String today = now.format(DATE_FORMATTER);
        String currentHour = now.format(HOUR_FORMATTER);
        String songMetadata = String.format("%s-%s", songTitle, songArtist);
        String sanitizedSongName = sanitizeFileName(songMetadata);

        Path songDir = Paths.get(outputDir, today, currentHour, sanitizedSongName);

        try {
            Files.createDirectories(songDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create directory: {}", songDir, e);
            return segments;
        }

        debugAudioProperties(audioFilePath);

        String baseName = UUID.randomUUID().toString();
        String segmentPattern = songDir + File.separator + baseName + "_%03d.ts";
        String segmentListFile = songDir + File.separator + baseName + "_segments.txt";

        try {
            FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(audioFilePath.toString())
                    .addOutput(segmentPattern)
                    .setAudioCodec("aac")
                    .setAudioBitRate(320000)
                    .setFormat("segment")
                    .addExtraArgs("-segment_time", String.valueOf(segmentDuration))
                    .addExtraArgs("-segment_format", "mpegts")
                    .addExtraArgs("-segment_list", segmentListFile)
                    .addExtraArgs("-segment_list_type", "flat")
                    .addExtraArgs("-ac", "2")
                    .addExtraArgs("-ar", "44100")
                    .addExtraArgs("-channel_layout", "stereo")
                    .addExtraArgs("-map", "0:a")
                    .addExtraArgs("-metadata", "title=" + songTitle)
                    .addExtraArgs("-metadata", "artist=" + songArtist)
                    .done();

            LOGGER.info("FFmpeg segmentation command: {}", builder.toString());

            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg.getFFmpeg());
            executor.createJob(builder).run();

            List<String> segmentFiles = Files.readAllLines(Paths.get(segmentListFile));

            if (!segmentFiles.isEmpty()) {
                String firstSegment = segmentFiles.get(0).trim();
                Path firstSegmentPath = Paths.get(songDir.toString(), firstSegment);
                LOGGER.info("Debugging first segment: {}", firstSegmentPath);
                debugAudioProperties(firstSegmentPath);
            }

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
            LOGGER.error("FFmpeg error for file: {}, error: {}", audioFilePath, e.getMessage());
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