package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;

import java.util.List;

public class WeekdayFilter {
    public static boolean isAllowed(Task task, String currentDay) {
        List<String> weekdays = getWeekdays(task);
        return weekdays == null || weekdays.isEmpty() || weekdays.contains(currentDay);
    }

    private static List<String> getWeekdays(Task task) {
        if (task.getTriggerType() == TriggerType.ONCE) {
            if (task.getOnceTrigger() != null) {
                return task.getOnceTrigger().getWeekdays();
            }
            return null;
        } else if (task.getTriggerType() == TriggerType.TIME_WINDOW) {
            if (task.getTimeWindowTrigger() != null) {
                return task.getTimeWindowTrigger().getWeekdays();
            }
            return null;
        } else if (task.getTriggerType() == TriggerType.PERIODIC) {
            if (task.getPeriodicTrigger() != null) {
                return task.getPeriodicTrigger().getWeekdays();
            }
            return null;
        }
        return null;
    }
}