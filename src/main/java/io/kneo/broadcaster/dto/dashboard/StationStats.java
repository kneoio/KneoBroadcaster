package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.controller.stream.Slide;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import lombok.Data;

@Data
public class StationStats {
    private String brandName;
    private RadioStationStatus status;
    private int segmentCount;
    private long currentSequence;
    private long firstSegmentKey;
    private long lastSegmentKey;
    private Slide currentSlide;
}
