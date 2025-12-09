package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.scheduler.OnceTrigger;
import io.kneo.broadcaster.model.scheduler.PeriodicTrigger;
import io.kneo.broadcaster.model.scheduler.Scheduler;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TimeWindowTrigger;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@ApplicationScoped
public class EventScheduleEvaluator {

    public boolean shouldFireNow(Scheduler scheduler, ZonedDateTime checkTime) {
        if (scheduler == null || !scheduler.isEnabled()) {
            return false;
        }
        List<Task> tasks = scheduler.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }

        ZoneId tz = scheduler.getTimeZone();
        if (tz == null) {
            tz = ZoneId.of("UTC");
        }
        ZonedDateTime localTime = checkTime.withZoneSameInstant(tz);

        for (Task task : tasks) {
            if (evaluateTask(task, localTime)) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateTask(Task task, ZonedDateTime now) {
        if (task.getTriggerType() == null) {
            return false;
        }

        return switch (task.getTriggerType()) {
            case ONCE -> evaluateOnceTrigger(task.getOnceTrigger(), now);
            case TIME_WINDOW -> evaluateTimeWindowTrigger(task.getTimeWindowTrigger(), now);
            case PERIODIC -> evaluatePeriodicTrigger(task.getPeriodicTrigger(), now);
        };
    }

    private boolean evaluateOnceTrigger(OnceTrigger trigger, ZonedDateTime now) {
        if (trigger == null || trigger.getStartTime() == null) {
            return false;
        }

        if (!matchesWeekday(trigger.getWeekdays(), now)) {
            return false;
        }

        LocalTime startTime = LocalTime.parse(trigger.getStartTime());
        LocalTime currentTime = now.toLocalTime();

        return currentTime.getHour() == startTime.getHour() 
            && currentTime.getMinute() == startTime.getMinute();
    }

    private boolean evaluateTimeWindowTrigger(TimeWindowTrigger trigger, ZonedDateTime now) {
        if (trigger == null || trigger.getStartTime() == null || trigger.getEndTime() == null) {
            return false;
        }

        if (!matchesWeekday(trigger.getWeekdays(), now)) {
            return false;
        }

        LocalTime startTime = LocalTime.parse(trigger.getStartTime());
        LocalTime endTime = LocalTime.parse(trigger.getEndTime());
        LocalTime currentTime = now.toLocalTime();

        boolean atStart = startTime.getHour() == currentTime.getHour() 
            && startTime.getMinute() == currentTime.getMinute();
        boolean withinWindow = !currentTime.isBefore(startTime) && !currentTime.isAfter(endTime);

        return atStart || withinWindow;
    }

    private boolean evaluatePeriodicTrigger(PeriodicTrigger trigger, ZonedDateTime now) {
        if (trigger == null || trigger.getStartTime() == null) {
            return false;
        }

        if (!matchesWeekday(trigger.getWeekdays(), now)) {
            return false;
        }

        LocalTime startTime = LocalTime.parse(trigger.getStartTime());
        LocalTime endTime = trigger.getEndTime() != null ? LocalTime.parse(trigger.getEndTime()) : LocalTime.of(23, 59);
        LocalTime currentTime = now.toLocalTime();

        if (currentTime.isBefore(startTime) || currentTime.isAfter(endTime)) {
            return false;
        }

        int intervalMinutes = trigger.getInterval();
        if (intervalMinutes <= 0) {
            return false;
        }

        int minutesSinceStart = (currentTime.getHour() - startTime.getHour()) * 60 
            + (currentTime.getMinute() - startTime.getMinute());

        return minutesSinceStart % intervalMinutes == 0;
    }

    private boolean matchesWeekday(List<String> weekdays, ZonedDateTime now) {
        if (weekdays == null || weekdays.isEmpty()) {
            return true;
        }

        DayOfWeek today = now.getDayOfWeek();
        String todayName = today.name();

        for (String day : weekdays) {
            if (day.equalsIgnoreCase(todayName) || day.equalsIgnoreCase(todayName.substring(0, 3))) {
                return true;
            }
        }
        return false;
    }
}
