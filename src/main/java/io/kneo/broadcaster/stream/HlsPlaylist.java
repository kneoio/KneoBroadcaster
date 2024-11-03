package io.kneo.broadcaster.stream;

import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class HlsPlaylist {
    private final ConcurrentNavigableMap<Integer, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private int currentSequence = 0;
    private static final int MAX_SEGMENTS = 10;
    private static final int SEGMENT_DURATION = 10; // seconds

    public synchronized void addSegment(byte[] data) {
        HlsSegment segment = new HlsSegment(currentSequence, data, SEGMENT_DURATION);
        segments.put(currentSequence, segment);
        currentSequence++;

        // Remove old segments if we exceed MAX_SEGMENTS
        while (segments.size() > MAX_SEGMENTS) {
            segments.pollFirstEntry();
        }
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

        // Don't add EXT-X-ENDLIST as this is a live stream

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

    // Method to get segment duration - useful for clients
    public static int getSegmentDuration() {
        return SEGMENT_DURATION;
    }
}
