package io.kneo.broadcaster.controller.stream;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents an HLS segment in the playlist
 * Enhanced with timestamp support for wall-clock alignment
 */
@Getter
@Setter
@NoArgsConstructor
public class HlsSegment {

    // Media sequence number (used in old implementation)
    private long sequenceNumber;

    // Unix timestamp for segment (aligned with wall clock)
    private long timestamp;

    // Human-readable name of the song in this segment
    private String songName;

    // Actual segment data (TS file content)
    private byte[] data;

    // Duration of this segment in seconds
    private float duration;

    // Size of this segment in bytes
    private long size;

    /**
     * Generate a standardized filename for this segment
     * Format: station_STATION-ID_TIMESTAMP.ts
     */
    public String generateFilename(String stationId) {
        return String.format("station_%s_%d.ts", stationId, timestamp);
    }
}