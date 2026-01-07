package io.kneo.broadcaster.model.cnst;

public enum StreamStatus {
    OFF_LINE, //off-line
    PENDING, //created, waiting for first listener to trigger start
    WARMING_UP, //started, in preparation stage
    ON_LINE, // on-line streaming
    QUEUE_SATURATED,
    IDLE, //on-line, no listeners
    FINISHED, //one-time stream completed all content
    SYSTEM_ERROR //something happened
}
