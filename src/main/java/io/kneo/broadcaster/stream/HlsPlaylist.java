package io.kneo.broadcaster.stream;

import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class HlsPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HlsPlaylist.class);
    private final ConcurrentNavigableMap<Integer, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private int currentSequence = 0;
    private static final int MAX_SEGMENTS = 10;
    private static final int SEGMENT_DURATION = 10; // seconds
    private static final int SEGMENT_RETENTION_COUNT = MAX_SEGMENTS * 2; // Keep twice the max segments temporarily

    public synchronized void addSegment(byte[] data) {
        HlsSegment segment = new HlsSegment(currentSequence, data, SEGMENT_DURATION);
        segments.put(currentSequence, segment);
        currentSequence++;

        // Remove old segments if we exceed MAX_SEGMENTS
        while (segments.size() > MAX_SEGMENTS) {
            segments.pollFirstEntry();
        }
    }

    public void cleanupOldSegments() {
        LOGGER.debug("Starting cleanup of old segments...");
        int oldestAllowedSequence = currentSequence - SEGMENT_RETENTION_COUNT;

        // Remove segments older than the retention count
        segments.headMap(oldestAllowedSequence).clear();

        LOGGER.debug("Cleaned up segments. Remaining segment count: {}", segments.size());
    }

    public String generatePlaylist() {
        if (segments.isEmpty()) {
            return null;
        }

        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n");
        playlist.append("#EXT-X-VERSION:3\n");
        playlist.append("#EXT-X-TARGETDURATION:").append(SEGMENT_DURATION).append("\n");

        // Get the first sequence number from our current segments
        int firstSequence = segments.firstKey();
        playlist.append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequence).append("\n");

        // Add each segment
        segments.values().forEach(segment -> {
            playlist.append("#EXTINF:").append(segment.getDuration()).append(".0,\n");
            playlist.append("segments/segment").append(segment.getSequenceNumber()).append(".ts\n");
        });

        return playlist.toString();
    }

    public HlsSegment getSegment(int sequence) {
        return segments.get(sequence);
    }

    public boolean hasSegment(int sequence) {
        return segments.containsKey(sequence);
    }

    public int getCurrentSequence() {
        return currentSequence;
    }

    public int getFirstSequence() {
        return segments.isEmpty() ? currentSequence : segments.firstKey();
    }

    public void clearSegments() {
        segments.clear();
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public static int getSegmentDuration() {
        return SEGMENT_DURATION;
    }
}