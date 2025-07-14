package io.kneo.broadcaster.service.manipulation;

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
        if (ffprobe == null) {
            LOGGER.warn("FFprobe not initialized, returning empty metadata");
            return createEmptyMetadata(filePath);
        }

        try {
            FFmpegProbeResult probeResult = ffprobe.probe(filePath);

            AudioMetadataDTO metadata = new AudioMetadataDTO();

            File file = new File(filePath);
            metadata.setFileName(file.getName());
            metadata.setFileSize(file.length());

            FFmpegFormat format = probeResult.getFormat();
            if (format != null) {
                metadata.setFormat(format.format_name);
                metadata.setDurationSeconds((int) format.duration);
                metadata.setDuration(Duration.ofSeconds((long) format.duration));
                metadata.setBitRate((int) format.bit_rate);

                Map<String, String> tags = format.tags;
                if (tags != null) {
                    metadata.setTitle(getTagValue(tags, "title", "TITLE"));
                    metadata.setArtist(getTagValue(tags, "artist", "ARTIST"));
                    metadata.setAlbum(getTagValue(tags, "album", "ALBUM"));
                    metadata.setAlbumArtist(getTagValue(tags, "album_artist", "ALBUMARTIST"));
                    metadata.setGenre(getTagValue(tags, "genre", "GENRE"));
                    metadata.setYear(getTagValue(tags, "date", "DATE", "year", "YEAR"));
                    metadata.setTrack(getTagValue(tags, "track", "TRACK"));
                    metadata.setComposer(getTagValue(tags, "composer", "COMPOSER"));
                    metadata.setComment(getTagValue(tags, "comment", "COMMENT"));
                    metadata.setPublisher(getTagValue(tags, "publisher", "PUBLISHER"));
                    metadata.setCopyright(getTagValue(tags, "copyright", "COPYRIGHT"));
                    metadata.setLanguage(getTagValue(tags, "language", "LANGUAGE"));
                }
            }

            // Stream metadata (audio technical details)
            FFmpegStream audioStream = probeResult.getStreams().stream()
                    .filter(stream -> stream.codec_type == FFmpegStream.CodecType.AUDIO)
                    .findFirst()
                    .orElse(null);

            if (audioStream != null) {
                metadata.setSampleRate(audioStream.sample_rate);
                metadata.setChannels(String.valueOf(audioStream.channels));
                metadata.setEncodingType(audioStream.codec_name);

                // Additional audio stream metadata
                if (audioStream.bit_rate > 0) {
                    metadata.setBitRate((int) audioStream.bit_rate);
                }

                // Check if it's lossless based on codec
                metadata.setLossless(isLosslessCodec(audioStream.codec_name));
            }

            LOGGER.info("Extracted metadata for file: {} - Title: {}, Artist: {}",
                    file.getName(), metadata.getTitle(), metadata.getArtist());
            return metadata;

        } catch (Exception e) {
            LOGGER.error("Failed to extract metadata from file: {}", filePath, e);
            return createEmptyMetadata(filePath);
        }
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
