package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import lombok.Data;

@Data
public class StationStats {
    private String brandName;
    private RadioStationStatus status;
    private int segmentsSize;
    private long lastSegmentKey;
    private long lastRequested;
}
