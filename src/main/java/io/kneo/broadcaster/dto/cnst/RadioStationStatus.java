package io.kneo.broadcaster.dto.cnst;

public enum RadioStationStatus {
    OFF_LINE, //off-line
    PENDING, //created, waiting for first listener to trigger start
    WARMING_UP, //started, in preparation stage
    ON_LINE, // on-line streaming
    QUEUE_SATURATED,
    IDLE, //on-line, no listeners
    SYSTEM_ERROR //something happened
}
