package io.kneo.broadcaster.service.scheduler;

import io.smallrye.mutiny.Uni;
import java.time.LocalTime;

public interface TaskExecutor {
    Uni<Void> execute(ScheduleExecutionContext context);
    void cleanupTasksForEntity(Object entity);

    boolean supports(CronTaskType taskType);

    default boolean isWithinTimeWindow(ScheduleExecutionContext context) {
        if (context.task().getTimeWindowTrigger() == null) return false;

        String currentTime = context.currentTime();
        String startTime = context.task().getTimeWindowTrigger().getStartTime();
        String endTime = context.task().getTimeWindowTrigger().getEndTime();

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime start = LocalTime.parse(startTime);
        LocalTime end = LocalTime.parse(endTime);

        return !current.isBefore(start) && !current.isAfter(end);
    }
}