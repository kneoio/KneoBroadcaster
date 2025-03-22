package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@Builder
public class PlaylistStats {
    private final String brandName;
    private final long currentSequence;
    private final long lastRequestedSegment;
    private final String lastRequestedFragmentName;
    private final Instant lastRequestedTimestamp;
    private final int segmentCount;
    private final long totalBytesProcessed;
    private final double bitrate;
    private final Instant nowTimestamp;
    private final int queueSize;
    private final List<String> recentlyPlayedTitles;
    private final int playlistAttrition;


    public static PlaylistStats fromPlaylist(HLSPlaylist playlist, List<String> recentlyPlayedTitles) {
        double estimatedBitrate = 0.0;
        if (playlist.getSegmentCount() > 0 && playlist.getLastRequestedSegment() > 0) {
            HlsSegment lastSegment = playlist.getSegment(playlist.getLastRequestedSegment());
            if (lastSegment != null) {
                estimatedBitrate = lastSegment.getBitrate();
            }
        }

        // Convert the long timestamp to Instant
        Instant lastTimestamp = playlist.getSegmentTimeStamp() > 0
                ? Instant.ofEpochSecond(playlist.getSegmentTimeStamp())
                : Instant.now();

        return PlaylistStats.builder()
                .brandName(playlist.getBrandName())
                .currentSequence(playlist.getCurrentSequence())
                .lastRequestedSegment(playlist.getLastRequestedSegment())
                .lastRequestedFragmentName(playlist.getLastRequestedFragmentName())
                .lastRequestedTimestamp(lastTimestamp)
                .segmentCount(playlist.getSegmentCount())
                .totalBytesProcessed(playlist.getTotalBytesProcessed())
                .bitrate(estimatedBitrate)
                .nowTimestamp(Instant.now())
                .queueSize(playlist.getQueueSize())
                .recentlyPlayedTitles(recentlyPlayedTitles)
                .build();
    }
}