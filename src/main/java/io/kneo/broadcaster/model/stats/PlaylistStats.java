package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.CurrentFragmentInfo;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

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
        Optional<CurrentFragmentInfo> optionalFragmentInfo = Optional.ofNullable(fragmentInfo);

        return PlaylistStats.builder()
                .brandName(playlist.getBrandName())
                .lastRequestedSegment(optionalFragmentInfo.map(CurrentFragmentInfo::getSequence).orElse(0L))
                .lastRequestedFragmentName(optionalFragmentInfo.map(CurrentFragmentInfo::getFragmentName).orElse(""))
                .lastRequestedTimestamp(optionalFragmentInfo.map(info -> Instant.ofEpochSecond(info.getTimestamp())).orElse(Instant.now()))
                .segmentCount(playlist.getSegmentCount())
                .totalBytesProcessed(playlist.getTotalBytesProcessed())
                .bitrate(optionalFragmentInfo.map(CurrentFragmentInfo::getBitrate).orElse(0))
                .nowTimestamp(Instant.now())
                .queueSize(playlist.getQueueSize())
                .build();
    }
}