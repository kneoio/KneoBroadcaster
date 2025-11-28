package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.AudioMetadataDTO;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegFormat;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

@ApplicationScoped
public class AudioMetadataService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioMetadataService.class);

    @Inject
    BroadcasterConfig config;

    private FFprobe ffprobe;

    void onStart(@Observes StartupEvent event) {
        try {
            ffprobe = new FFprobe(config.getFfprobePath());
            LOGGER.info("FFprobe initialized with path: {}", config.getFfprobePath());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize FFprobe with path: {}", config.getFfprobePath(), e);
        }
    }

    public AudioMetadataDTO extractMetadata(String filePath) {
        return extractMetadataWithProgress(filePath, null);
    }

    public AudioMetadataDTO extractMetadataWithProgress(String filePath, Consumer<Integer> progressCallback) {
        if (ffprobe == null) {
            LOGGER.warn("FFprobe not initialized, returning empty metadata");
            return createEmptyMetadata(filePath);
        }

        try {
            AudioMetadataDTO metadata = new AudioMetadataDTO();
            File file = new File(filePath);

            // Step 1: Basic file info (55% - 60%)
            reportProgress(progressCallback, 55);
            metadata.setFileName(file.getName());
            metadata.setFileSize(file.length());

            // Small delay to make progress visible
            Thread.sleep(50);
            reportProgress(progressCallback, 60);

            // Step 2: Probe the file (60% - 65%)
            //LOGGER.info("Starting FFprobe analysis for file: {}", filePath);
            FFmpegProbeResult probeResult = ffprobe.probe(filePath);
            reportProgress(progressCallback, 65);

            // Step 3: Extract format information (65% - 70%)
            FFmpegFormat format = probeResult.getFormat();
            if (format != null) {
                metadata.setFormat(format.format_name);
                metadata.setLength(Duration.ofSeconds((long) format.duration));
                metadata.setBitRate((int) format.bit_rate);

              //  LOGGER.info("Format extracted: {} duration: {}s", format.format_name, format.duration);
            }

            Thread.sleep(50);
            reportProgress(progressCallback, 70);

            // Step 4: Extract basic tag information (70% - 75%)
            if (format != null && format.tags != null) {
                Map<String, String> tags = format.tags;
                metadata.setTitle(getTagValue(tags, "title", "TITLE"));
                metadata.setArtist(getTagValue(tags, "artist", "ARTIST"));
                metadata.setAlbum(getTagValue(tags, "album", "ALBUM"));

                LOGGER.info("Basic tags extracted - Title: {}, Artist: {}, Album: {}",
                        metadata.getTitle(), metadata.getArtist(), metadata.getAlbum());
            }

            Thread.sleep(50);
            reportProgress(progressCallback, 75);

            // Step 5: Extract additional tag information (75% - 80%)
            if (format != null && format.tags != null) {
                Map<String, String> tags = format.tags;
                metadata.setAlbumArtist(getTagValue(tags, "album_artist", "ALBUMARTIST"));
                metadata.setGenre(getTagValue(tags, "genre", "GENRE"));
                metadata.setYear(getTagValue(tags, "date", "DATE", "year", "YEAR"));
                metadata.setTrack(getTagValue(tags, "track", "TRACK"));
                metadata.setComposer(getTagValue(tags, "composer", "COMPOSER"));
                metadata.setComment(getTagValue(tags, "comment", "COMMENT"));
                metadata.setPublisher(getTagValue(tags, "publisher", "PUBLISHER"));
                metadata.setCopyright(getTagValue(tags, "copyright", "COPYRIGHT"));
                metadata.setLanguage(getTagValue(tags, "language", "LANGUAGE"));

                LOGGER.info("Extended metadata extracted - Genre: {}, Year: {}",
                        metadata.getGenre(), metadata.getYear());
            }

            Thread.sleep(50);
            reportProgress(progressCallback, 80);

            // Step 6: Extract audio stream technical details (80% - 85%)
            FFmpegStream audioStream = probeResult.getStreams().stream()
                    .filter(stream -> stream.codec_type == FFmpegStream.CodecType.AUDIO)
                    .findFirst()
                    .orElse(null);

            if (audioStream != null) {
                metadata.setSampleRate(audioStream.sample_rate);
                metadata.setChannels(String.valueOf(audioStream.channels));
                metadata.setEncodingType(audioStream.codec_name);

                if (audioStream.bit_rate > 0) {
                    metadata.setBitRate((int) audioStream.bit_rate);
                }

                metadata.setLossless(isLosslessCodec(audioStream.codec_name));

               // LOGGER.info("Audio stream info - Codec: {}, Sample Rate: {}, Channels: {}", audioStream.codec_name, audioStream.sample_rate, audioStream.channels);
            }

            Thread.sleep(50);
            reportProgress(progressCallback, 85);

            LOGGER.info("Successfully extracted complete metadata for file: {} - Title: {}, Artist: {}",
                    file.getName(), metadata.getTitle(), metadata.getArtist());

            return metadata;

        } catch (Exception e) {
            LOGGER.error("Failed to extract metadata from file: {}", filePath, e);
            return createEmptyMetadata(filePath);
        }
    }

    private void reportProgress(Consumer<Integer> progressCallback, int percentage) {
        if (progressCallback != null) {
            try {
                progressCallback.accept(percentage);
            } catch (Exception e) {
                LOGGER.warn("Failed to report progress: {}", e.getMessage());
            }
        }
    }

    public AudioMetadataDTO extractBasicInfo(String filePath) {
        AudioMetadataDTO metadata = new AudioMetadataDTO();
        File file = new File(filePath);
        metadata.setFileName(file.getName());
        metadata.setFileSize(file.length());
        return metadata;
    }

 private String getTagValue(Map<String, String> tags, String... keys) {
        for (String key : keys) {
            String value = tags.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isLosslessCodec(String codecName) {
        if (codecName == null) return false;
        String codec = codecName.toLowerCase();
        return codec.contains("flac") || codec.contains("alac") ||
                codec.contains("ape") || codec.contains("wav") ||
                codec.contains("pcm");
    }

    private AudioMetadataDTO createEmptyMetadata(String filePath) {
        AudioMetadataDTO metadata = new AudioMetadataDTO();
        File file = new File(filePath);
        metadata.setFileName(file.getName());
        metadata.setFileSize(file.length());
        return metadata;
    }
}