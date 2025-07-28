package io.kneo.broadcaster.service.scheduler;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
public class TaskState {
    private final UUID entityId;
    private final String taskType;
    private final String target;
    private final String brand;  // NEW for dashboard filtering
    private final LocalDateTime startTime;

    public TaskState(UUID entityId, String taskType, String target, String brand, LocalDateTime startTime) {
        this.entityId = entityId;
        this.taskType = taskType;
        this.target = target;
        this.brand = brand;
        this.startTime = startTime;
    }
}
