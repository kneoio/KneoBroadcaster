package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;

import java.time.LocalDateTime;

public record StatusChangeRecord(LocalDateTime timestamp, RadioStationStatus oldStatus,
                                 RadioStationStatus newStatus) {
}
