package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.model.cnst.StreamStatus;

import java.time.LocalDateTime;

public record StatusChangeRecord(LocalDateTime timestamp, StreamStatus oldStatus,
                                 StreamStatus newStatus) {
}
