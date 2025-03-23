package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import lombok.Data;

import java.time.Instant;

@Data
public class StationStats {
    private String brandName;
    private RadioStationStatus status;
    private int segmentsSize;
    private long lastSegmentKey;
    private long lastRequested;
    private Instant lastSegmentTimestamp;
    private PlaylistManagerStats playlistManagerStats;

    private long totalBytesProcessed;
    private double bitrate;
    private int queueSize;
    private Instant lastUpdated;
}