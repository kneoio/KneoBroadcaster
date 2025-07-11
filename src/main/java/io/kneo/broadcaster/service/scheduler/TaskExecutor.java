package io.kneo.broadcaster.service.scheduler;

import io.smallrye.mutiny.Uni;

public interface TaskExecutor {
    Uni<Void> execute(ScheduleExecutionContext context);
    boolean supports(String taskType);
}
