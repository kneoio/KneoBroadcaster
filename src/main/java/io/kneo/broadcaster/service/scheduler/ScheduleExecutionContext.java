package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Task;

public record ScheduleExecutionContext(Schedulable entity, Task task, String currentTime) {
}
