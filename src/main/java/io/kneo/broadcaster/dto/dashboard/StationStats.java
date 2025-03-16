package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class StationStats {
    private String brandName;
    private RadioStationStatus status;
    private int segmentsSize;
    private long lastSegmentKey;
    private long lastRequested;
    private String currentFragment;

    // New fields from PlaylistStats
    private long totalBytesProcessed;
    private double bitrate; // in kbps
    private int queueSize;
    private List<String> recentlyPlayedTitles;
    private Instant lastUpdated;
}