package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable snapshot of HLS playlist metrics for dashboard display
 */
@Getter
@Builder
public class PlaylistStats {
    private final String brandName;
    private final long currentSequence;
    private final long lastRequestedSegment;
    private final String lastRequestedFragmentName;
    private final int segmentCount;
    private final long totalBytesProcessed;
    private final double bitrate; // in kbps
    private final Instant timestamp;
    private final int queueSize;
    private final List<String> recentlyPlayedTitles;

    /**
     * Creates a stats snapshot from the current playlist state
     */
    public static PlaylistStats fromPlaylist(HLSPlaylist playlist, List<String> recentlyPlayedTitles) {
        // Calculate approximate bitrate based on last segment if available
        double estimatedBitrate = 0.0;
        if (playlist.getSegmentCount() > 0 && playlist.getLastRequestedSegment() > 0) {
            HlsSegment lastSegment = playlist.getSegment(playlist.getLastRequestedSegment());
            if (lastSegment != null) {
                estimatedBitrate = lastSegment.getBitrate();
            }
        }

        return PlaylistStats.builder()
                .brandName(playlist.getBrandName())
                .currentSequence(playlist.getCurrentSequence())
                .lastRequestedSegment(playlist.getLastRequestedSegment())
                .lastRequestedFragmentName(playlist.getLastRequestedFragmentName())
                .segmentCount(playlist.getSegmentCount())
                .totalBytesProcessed(getTotalBytes(playlist))
                .bitrate(estimatedBitrate)
                .timestamp(Instant.now())
                .queueSize(getQueueSize(playlist))
                .recentlyPlayedTitles(recentlyPlayedTitles)
                .build();
    }

    private static long getTotalBytes(HLSPlaylist playlist) {
        try {
            // Use reflection to safely access totalBytesProcessed
            java.lang.reflect.Field field = HLSPlaylist.class.getDeclaredField("totalBytesProcessed");
            field.setAccessible(true);
            AtomicLong totalBytes = (AtomicLong) field.get(playlist);
            return totalBytes.get();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int getQueueSize(HLSPlaylist playlist) {
        try {
            // Use reflection to safely access mainQueue size
            java.lang.reflect.Field field = HLSPlaylist.class.getDeclaredField("mainQueue");
            field.setAccessible(true);
            return ((java.util.Queue<?>) field.get(playlist)).size();
        } catch (Exception e) {
            return 0;
        }
    }
}