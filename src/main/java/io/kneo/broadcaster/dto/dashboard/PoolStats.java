package io.kneo.broadcaster.dto.dashboard;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class PoolStats {
    private int totalStations;
    private int onlineStations;
    private int minimumSegments;
    private int slidingWindowSize;
    private Map<String, StationStats> stations = new HashMap<>();
}

