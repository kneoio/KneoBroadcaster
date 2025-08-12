package io.kneo.broadcaster.service.scheduler.quartz.handlers;

import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;
import io.kneo.broadcaster.service.scheduler.CronTaskType;
import io.kneo.broadcaster.service.scheduler.quartz.EventTriggerJob;
import io.kneo.broadcaster.service.scheduler.quartz.spi.TaskSchedulerHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.TimeZone;

import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

@ApplicationScoped
public class EventTriggerTaskScheduler implements TaskSchedulerHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventTriggerTaskScheduler.class);

    @Inject
    Scheduler scheduler;

    @Override
    public boolean supports(Schedulable entity, Task task) {
        if (!(entity instanceof Event)) return false;
        if (task == null) return false;
        if (task.getType() != CronTaskType.EVENT_TRIGGER) return false;
        if (task.getTriggerType() == TriggerType.TIME_WINDOW) return task.getTimeWindowTrigger() != null;
        if (task.getTriggerType() == TriggerType.PERIODIC) return task.getPeriodicTrigger() != null;
        return false;
    }

    @Override
    public void schedule(Schedulable entity, Task task, ZoneId timeZone) throws SchedulerException {
        Event event = (Event) entity;
        removeFor(entity);
        if (task.getTriggerType() == TriggerType.TIME_WINDOW) {
            String startTime = task.getTimeWindowTrigger().getStartTime();
            scheduleEventJob(event, startTime, timeZone);
        } else if (task.getTriggerType() == TriggerType.PERIODIC) {
            schedulePeriodicEvent(event, timeZone, task);
        }
    }

    @Override
    public void removeFor(Schedulable entity) throws SchedulerException {
        if (!(entity instanceof Event event)) return;
        UUID id = event.getId();
        if (id == null) return;
        String key = id + "_event_trigger";
        scheduler.deleteJob(JobKey.jobKey(key, "event"));
    }

    private void scheduleEventJob(Event event, String startTime, ZoneId timeZone) throws SchedulerException {
        UUID id = event.getId();
        String brandId = event.getBrand() != null ? event.getBrand().toString() : "";
        String jobKey = id + "_event_trigger";
        String cronExpression = convertTimeToCron(startTime);
        JobDetail job = newJob(EventTriggerJob.class)
                .withIdentity(jobKey, "event")
                .usingJobData("eventId", id != null ? id.toString() : "")
                .usingJobData("brandId", brandId)
                .usingJobData("type", event.getType() != null ? event.getType().name() : "")
                .usingJobData("description", event.getDescription() != null ? event.getDescription() : "")
                .usingJobData("priority", event.getPriority() != null ? event.getPriority().name() : "")
                .build();
        Trigger trigger = newTrigger()
                .withIdentity(jobKey + "_trigger", "event")
                .withSchedule(cronSchedule(cronExpression).inTimeZone(TimeZone.getTimeZone(timeZone)))
                .build();
        scheduler.scheduleJob(job, trigger);
        LOGGER.info("Scheduled event {} at {}", id, startTime);
    }

    private void schedulePeriodicEvent(Event event, ZoneId timeZone, Task task) throws SchedulerException {
        UUID id = event.getId();
        String brandId = event.getBrand() != null ? event.getBrand().toString() : "";
        String jobKey = id + "_event_trigger";
        LocalTime start = LocalTime.parse(task.getPeriodicTrigger().getStartTime());
        LocalTime end = LocalTime.parse(task.getPeriodicTrigger().getEndTime());
        int interval = task.getPeriodicTrigger().getInterval();
        List<String> dows = convertWeekdaysToAbbrev(task.getPeriodicTrigger().getWeekdays());
        JobDetail job = newJob(EventTriggerJob.class)
                .withIdentity(jobKey, "event")
                .usingJobData("eventId", id != null ? id.toString() : "")
                .usingJobData("brandId", brandId)
                .usingJobData("type", event.getType() != null ? event.getType().name() : "")
                .usingJobData("description", event.getDescription() != null ? event.getDescription() : "")
                .usingJobData("priority", event.getPriority() != null ? event.getPriority().name() : "")
                .build();

        Set<Trigger> triggers = new HashSet<>();
        
        // Handle cross-midnight periods (e.g., 19:00 to 05:45 next day)
        if (end.isBefore(start)) {
            // Schedule from start time to midnight
            for (LocalTime t = start; t.isBefore(LocalTime.MAX) || t.equals(LocalTime.MAX); t = t.plusMinutes(interval)) {
                String cron = buildCronForInstant(t.getHour(), t.getMinute(), dows);
                Trigger trig = newTrigger()
                        .withIdentity(jobKey + "_trg_" + t.toString(), "event")
                        .withSchedule(cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(timeZone)))
                        .build();
                triggers.add(trig);
                
                // Stop if next interval would exceed midnight
                if (t.plusMinutes(interval).isBefore(t)) break;
            }
            
            // Schedule from midnight to end time
            for (LocalTime t = LocalTime.MIDNIGHT; !t.isAfter(end); t = t.plusMinutes(interval)) {
                String cron = buildCronForInstant(t.getHour(), t.getMinute(), dows);
                Trigger trig = newTrigger()
                        .withIdentity(jobKey + "_trg_midnight_" + t.toString(), "event")
                        .withSchedule(cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(timeZone)))
                        .build();
                triggers.add(trig);
            }
        } else {
            // Normal same-day scheduling
            for (LocalTime t = start; !t.isAfter(end); t = t.plusMinutes(interval)) {
                String cron = buildCronForInstant(t.getHour(), t.getMinute(), dows);
                Trigger trig = newTrigger()
                        .withIdentity(jobKey + "_trg_" + t.toString(), "event")
                        .withSchedule(cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(timeZone)))
                        .build();
                triggers.add(trig);
            }
        }
        scheduler.scheduleJob(job, triggers, true);
        LOGGER.info("Scheduled periodic event {} interval {}m from {} to {}", id, interval, start, end);
    }

    private String convertTimeToCron(String time) {
        LocalTime localTime = LocalTime.parse(time);
        return String.format("0 %d %d * * ?", localTime.getMinute(), localTime.getHour());
    }

    private List<String> convertWeekdaysToAbbrev(java.util.List<String> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) return List.of("MON","TUE","WED","THU","FRI","SAT","SUN");
        List<String> out = new ArrayList<>();
        for (String d : weekdays) {
            if (d == null) continue;
            switch (d.toUpperCase()) {
                case "MON", "MONDAY" -> out.add("MON");
                case "TUE", "TUESDAY" -> out.add("TUE");
                case "WED", "WEDNESDAY" -> out.add("WED");
                case "THU", "THURSDAY" -> out.add("THU");
                case "FRI", "FRIDAY" -> out.add("FRI");
                case "SAT", "SATURDAY" -> out.add("SAT");
                case "SUN", "SUNDAY" -> out.add("SUN");
                default -> {}
            }
        }
        if (out.isEmpty()) return List.of("MON","TUE","WED","THU","FRI","SAT","SUN");
        return out;
    }

    private String buildCronForInstant(int hour, int minute, List<String> dows) {
        String days = String.join(",", dows);
        return String.format("0 %d %d ? * %s", minute, hour, days);
    }
}
