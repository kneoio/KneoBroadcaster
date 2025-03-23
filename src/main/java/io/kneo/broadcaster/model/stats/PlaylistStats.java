package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.CurrentFragmentInfo;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class PlaylistStats {
    private final String brandName;
    private final long lastRequestedSegment;
    private final String lastRequestedFragmentName;
    private final Instant lastRequestedTimestamp;
    private final int segmentCount;
    private final long totalBytesProcessed;
    private final double bitrate;
    private final Instant nowTimestamp;
    private final int queueSize;


    public static PlaylistStats fromPlaylist(HLSPlaylist playlist, CurrentFragmentInfo fragmentInfo) {

        return PlaylistStats.builder()
                .brandName(playlist.getBrandName())
                .lastRequestedSegment(fragmentInfo.getSequence())
                .lastRequestedFragmentName(fragmentInfo.getFragmentName())
                .lastRequestedTimestamp(Instant.ofEpochSecond(fragmentInfo.getTimestamp()))
                .segmentCount(playlist.getSegmentCount())
                .totalBytesProcessed(playlist.getTotalBytesProcessed())
                .bitrate(fragmentInfo.getBitrate())
                .nowTimestamp(Instant.now())
                .queueSize(playlist.getQueueSize())
                .build();

    }
}