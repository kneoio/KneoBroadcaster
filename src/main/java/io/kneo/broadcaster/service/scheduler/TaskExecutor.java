package io.kneo.broadcaster.service.scheduler;

import io.smallrye.mutiny.Uni;
import java.time.LocalTime;

public interface TaskExecutor {
    Uni<Void> execute(ScheduleExecutionContext context);

    boolean supports(CronTaskType taskType);

    default boolean isAtWindowStart(ScheduleExecutionContext context) {
        if (context.getTask().getTimeWindowTrigger() == null) return false;

        String currentTime = context.getCurrentTime();
        String startTime = context.getTask().getTimeWindowTrigger().getStartTime();

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime start = LocalTime.parse(startTime);

        return !current.isBefore(start);
    }

    default boolean isAtWindowEnd(ScheduleExecutionContext context) {
        if (context.getTask().getTimeWindowTrigger() == null) return false;

        String currentTime = context.getCurrentTime();
        String endTime = context.getTask().getTimeWindowTrigger().getEndTime();

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime end = LocalTime.parse(endTime);

        return !current.isBefore(end);
    }

    default boolean isWithinTimeWindow(ScheduleExecutionContext context) {
        if (context.getTask().getTimeWindowTrigger() == null) return false;

        String currentTime = context.getCurrentTime();
        String startTime = context.getTask().getTimeWindowTrigger().getStartTime();
        String endTime = context.getTask().getTimeWindowTrigger().getEndTime();

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime start = LocalTime.parse(startTime);
        LocalTime end = LocalTime.parse(endTime);

        return !current.isBefore(start) && !current.isAfter(end);
    }
}