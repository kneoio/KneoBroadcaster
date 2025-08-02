package io.kneo.broadcaster.dto.cnst;

public enum RadioStationStatus {
    OFF_LINE, //off-line
    WARMING_UP, //started, in preparation stage
    ON_LINE, // on-line streaming
    QUEUE_SATURATED,
    WAITING_FOR_CURATOR, //on-line but waiting to be curated
    IDLE, //on-line, no listeners
    SYSTEM_ERROR //something happened
}
