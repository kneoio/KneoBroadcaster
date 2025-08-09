package io.kneo.broadcaster.service.scheduler;

import java.time.LocalDateTime;
import java.util.UUID;

public record TaskState(UUID entityId, String taskType, String target, String brand, LocalDateTime startTime) {
}
