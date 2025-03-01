package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.SoundFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Playlist {
    private static final Logger LOGGER = LoggerFactory.getLogger(Playlist.class);
    private final ConcurrentNavigableMap<Integer, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicInteger currentSequence = new AtomicInteger(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicInteger segmentsCreated = new AtomicInteger(0);
    private final HlsPlaylistConfig config;

    public Playlist(HlsPlaylistConfig config) {
        this.config = config;
    }

    public String generatePlaylist() {
        StringBuilder playlist = new StringBuilder(segments.size() * 100);
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                .append("#EXT-X-MEDIA-SEQUENCE:").append(segments.firstKey()).append("\n");

        segments.values().forEach(segment -> {
            playlist.append("#EXTINF:").append(segment.getDuration()).append(",\n")
                    .append("segments/").append(segment.getSequenceNumber()).append(".ts\n");
        });

        return playlist.toString();
    }

    public HlsSegment getSegment(int sequence) {
        return segments.get(sequence);
    }

    public int getSegmentCount() {
        return segments.size();
    }

    @Deprecated
    public void addSegment(SoundFragment fragment) {
        if (fragment == null || fragment.getFile() == null || fragment.getFile().length == 0) {
            LOGGER.warn("Attempted to add empty segment");
            return;
        }

        int sequence = currentSequence.getAndIncrement();
        HlsSegment segment = new HlsSegment(sequence, fragment, config.getSegmentDuration());
        segments.put(sequence, segment);

        totalBytesProcessed.addAndGet(fragment.getFile().length);
        segmentsCreated.incrementAndGet();

        cleanupIfNeeded(sequence);
    }

    public void addSegment(SoundFragment fragment, String brand) {
        if (fragment == null ) {
            LOGGER.warn("Attempted to add empty segment");
            return;
        }

        if (fragment.getFilePath() != null) {
            try {
                Path sourcePath = Paths.get(fragment.getFilePath());
                Path brandDir = Paths.get(config.getBaseDir(), brand);
                Files.createDirectories(brandDir);
                Path targetPath = brandDir.resolve(sourcePath.getFileName());
                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                fragment.setFilePath(targetPath.toString());
            } catch (IOException e) {
                LOGGER.error("Failed to move file to brand directory: {}", brand, e);
                return;
            }
        }

        int sequence = currentSequence.getAndIncrement();
        HlsSegment segment = new HlsSegment(sequence, fragment, config.getSegmentDuration());
        segments.put(sequence, segment);

        totalBytesProcessed.addAndGet(fragment.getFile().length);
        segmentsCreated.incrementAndGet();

        cleanupIfNeeded(sequence);
    }

    private void cleanupIfNeeded(int currentSeq) {
        if (segments.size() > config.getMaxSegments()) {
            int oldestAllowed = Math.max(currentSeq - config.getMaxSegments(), 0);
            segments.headMap(oldestAllowed).clear();
        }
    }
}