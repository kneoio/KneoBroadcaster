package io.kneo.broadcaster.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
public class HlsPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HlsPlaylist.class);

    private final ConcurrentNavigableMap<Integer, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicInteger currentSequence = new AtomicInteger(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicInteger segmentsCreated = new AtomicInteger(0);

    private final HlsPlaylistConfig config;

    @Inject
    public HlsPlaylist(HlsPlaylistConfig config) {
        this.config = config;
        LOGGER.info("Initialized HLS Playlist with maxSegments={}, duration={}, bitrate={}kbps",
                config.getMaxSegments(), config.getSegmentDuration(), config.getBitrate());
    }

    public void addSegment(byte[] data) {
        if (data == null || data.length == 0) {
            LOGGER.warn("Attempted to add empty segment");
            return;
        }

        if (data.length > config.getBufferSizeKb() * 1024) {
            LOGGER.warn("Segment size {} exceeds maximum buffer size {}",
                    data.length, config.getBufferSizeKb() * 1024);
            return;
        }

        int sequence = currentSequence.getAndIncrement();
        HlsSegment segment = new HlsSegment(sequence, data, config.getSegmentDuration());
        segments.put(sequence, segment);

        totalBytesProcessed.addAndGet(data.length);
        segmentsCreated.incrementAndGet();

        cleanupIfNeeded(sequence);
        if (config.isMonitoringEnabled()) {
            logMetrics();
        }
    }

    private void cleanupIfNeeded(int currentSeq) {
        if (segments.size() > config.getMaxSegments()) {
            int oldestAllowed = currentSeq - config.getMaxSegments();
            Map<Integer, HlsSegment> removedSegments = new HashMap<>(segments.headMap(oldestAllowed));
            segments.headMap(oldestAllowed).clear();

            if (config.isMonitoringEnabled()) {
                long freedBytes = removedSegments.values().stream()
                        .mapToLong(segment -> segment.getData().length)
                        .sum();
                LOGGER.debug("Cleaned up {} segments, freed {} bytes",
                        removedSegments.size(), freedBytes);
            }
        }
    }

    private void logMetrics() {
        if (segmentsCreated.get() % 10 == 0) {  // Log every 10 segments
            LOGGER.info("Playlist metrics - Segments: {}, Memory: {}MB, Avg Segment Size: {}KB",
                    segments.size(),
                    totalBytesProcessed.get() / (1024 * 1024),
                    segments.isEmpty() ? 0 : totalBytesProcessed.get() / (segmentsCreated.get() * 1024));
        }
    }

    public String generatePlaylist() {
        if (segments.isEmpty()) {
            LOGGER.warn("Attempted to generate playlist with no segments");
            return null;
        }

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
        HlsSegment segment = segments.get(sequence);
        if (segment == null && config.isMonitoringEnabled()) {
            LOGGER.debug("Segment {} not found", sequence);
        }
        return segment;
    }

    // Essential getters
    public int getCurrentSequence() {
        return currentSequence.get();
    }

    public int getFirstSequence() {
        return segments.isEmpty() ? currentSequence.get() : segments.firstKey();
    }

    // Monitoring getters (only if monitoring enabled)
    public int getSegmentCount() {
        return segments.size();
    }

    public long getTotalBytesProcessed() {
        return totalBytesProcessed.get();
    }

    public int getTotalSegmentsCreated() {
        return segmentsCreated.get();
    }

    public double getAverageSegmentSize() {
        return segmentsCreated.get() == 0 ? 0 :
                totalBytesProcessed.get() / (double) segmentsCreated.get();
    }
}