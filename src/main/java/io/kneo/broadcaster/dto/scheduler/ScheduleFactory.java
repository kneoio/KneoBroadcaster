package io.kneo.broadcaster.dto.scheduler;

import io.kneo.broadcaster.model.scheduler.TriggerType;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class ScheduleFactory {

    public static ScheduleDTO createWorkdaySchedule(int intervalMinutes) {
        PeriodicTriggerDTO periodicTrigger = new PeriodicTriggerDTO();
        periodicTrigger.setStartTime("09:00");
        periodicTrigger.setEndTime("22:00");
        periodicTrigger.setInterval(intervalMinutes);
        periodicTrigger.setWeekdays(Arrays.asList("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"));

        TaskDTO task = new TaskDTO();
        task.setId(UUID.randomUUID());
        task.setTriggerType(TriggerType.PERIODIC);
        task.setPeriodicTrigger(periodicTrigger);

        ScheduleDTO schedule = new ScheduleDTO();
        schedule.setEnabled(true);
        schedule.setTasks(List.of(task));

        return schedule;
    }
}