package io.kneo.broadcaster.service.scheduler.quartz.handlers;

import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.scheduler.ScheduledTaskType;
import io.kneo.broadcaster.service.scheduler.job.EventTriggerJob;
import io.kneo.broadcaster.service.scheduler.quartz.QuartzUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import static io.kneo.broadcaster.service.scheduler.quartz.QuartzUtils.buildCronForInstant;
import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

@ApplicationScoped
public class EventTriggerJobRunner implements TaskSchedulerHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventTriggerJobRunner.class);

    @Inject
    Scheduler scheduler;

    @Inject
    RadioStationService radioStationService;

    @Override
    public boolean supports(Schedulable entity, Task task) {
        if (!(entity instanceof Event)) return false;
        if (task == null) return false;
        if (task.getType() != ScheduledTaskType.EVENT_TRIGGER) return false;
        if (task.getTriggerType() == TriggerType.TIME_WINDOW) return task.getTimeWindowTrigger() != null;
        if (task.getTriggerType() == TriggerType.PERIODIC) return task.getPeriodicTrigger() != null;
        return false;
    }

    @Override
    public void schedule(Schedulable entity, Task task, ZoneId timeZone) throws SchedulerException {
        Event event = (Event) entity;
        removeFor(entity);

        radioStationService.findByBrandName(event.getBrand().toString())
                .subscribe().with(
                        radioStation -> {
                            try {
                                String slugName = radioStation.getSlugName();
                                if (task.getTriggerType() == TriggerType.PERIODIC) {
                                    schedulePeriodicEventWithSlugName(event, timeZone, task, slugName);
                                }
                            } catch (SchedulerException e) {
                                LOGGER.error("Failed to schedule event {}: {}", event.getId(), e.getMessage());
                            }
                        },
                        failure -> LOGGER.error("Failed to find radio station for brand {}: {}",
                                event.getBrand(), failure.getMessage())
                );
    }

    @Override
    public void removeFor(Schedulable entity) throws SchedulerException {
        if (!(entity instanceof Event event)) return;
        UUID id = event.getId();
        if (id == null) return;
        String key = id + "_event_trigger";
        scheduler.deleteJob(JobKey.jobKey(key, "event"));
    }

    private void schedulePeriodicEventWithSlugName(Event event, ZoneId timeZone, Task task, String slugName) throws SchedulerException {
        UUID id = event.getId();
        String jobKey = id + "_event_trigger";
        LocalTime start = LocalTime.parse(task.getPeriodicTrigger().getStartTime());
        LocalTime end = LocalTime.parse(task.getPeriodicTrigger().getEndTime());
        int interval = task.getPeriodicTrigger().getInterval();
        List<String> dows = QuartzUtils.convertWeekdaysToAbbrev(task.getPeriodicTrigger().getWeekdays());
        JobDetail job = newJob(EventTriggerJob.class)
                .withIdentity(jobKey, "event")
                .usingJobData("eventId", id.toString())
                .usingJobData("slugName", slugName)
                .usingJobData("type", event.getType().name())
                .build();

        Set<Trigger> triggers = new HashSet<>();

        if (end.isBefore(start)) {
            for (LocalTime t = start; t.isBefore(LocalTime.MAX) || t.equals(LocalTime.MAX); t = t.plusMinutes(interval)) {
                String cron = buildCronForInstant(t.getHour(), t.getMinute(), dows);
                Trigger trig = newTrigger()
                        .withIdentity(jobKey + "_trg_" + t.toString(), "event")
                        .withSchedule(cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(timeZone)))
                        .build();
                triggers.add(trig);

                if (t.plusMinutes(interval).isBefore(t)) break;
            }

            for (LocalTime t = LocalTime.MIDNIGHT; !t.isAfter(end); t = t.plusMinutes(interval)) {
                String cron = buildCronForInstant(t.getHour(), t.getMinute(), dows);
                Trigger trig = newTrigger()
                        .withIdentity(jobKey + "_trg_midnight_" + t.toString(), "event")
                        .withSchedule(cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(timeZone)))
                        .build();
                triggers.add(trig);
            }
        } else {
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

}