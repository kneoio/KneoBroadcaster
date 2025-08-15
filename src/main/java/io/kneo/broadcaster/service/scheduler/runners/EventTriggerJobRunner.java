package io.kneo.broadcaster.service.scheduler.runners;

import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.scheduler.ScheduledTaskType;
import io.kneo.broadcaster.service.scheduler.job.EventTriggerJob;
import io.kneo.broadcaster.service.scheduler.quartz.QuartzUtils;
import io.kneo.core.model.user.SuperUser;
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
        LOGGER.info("Found scheduled periodic event {}", event.getBrandId());
        radioStationService.getById(event.getBrandId(), SuperUser.build())
                .subscribe().with(
                        radioStation -> {
                            try {
                                String slugName = radioStation.getSlugName();
                                if (task.getTriggerType() == TriggerType.PERIODIC) {
                                    schedulePeriodicEventWithSlugName(event, timeZone, task, slugName);
                                    // Add recovery check after scheduling
                                    checkAndResumeIfActive(event, task, timeZone, slugName);
                                }
                            } catch (SchedulerException e) {
                                LOGGER.error("Failed to schedule event {}: {}", event.getId(), e.getMessage());
                            }
                        },
                        failure -> LOGGER.error("Failed to find radio station for brand {}: {}",
                                event.getBrandId(), failure.getMessage())
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

    private void checkAndResumeIfActive(Event event, Task task, ZoneId timeZone, String slugName) {
        try {
            LocalTime now = LocalTime.now(timeZone);
            LocalTime start = LocalTime.parse(task.getPeriodicTrigger().getStartTime());
            LocalTime end = LocalTime.parse(task.getPeriodicTrigger().getEndTime());

            if (isWithinActiveWindow(now, start, end)) {
                LOGGER.info("Event {} is currently active, triggering immediate start", event.getId());
                triggerEventImmediately(event, slugName);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to check recovery for event {}: {}", event.getId(), e.getMessage());
        }
    }

    private boolean isWithinActiveWindow(LocalTime now, LocalTime start, LocalTime end) {
        if (end.isBefore(start)) {
            return !now.isBefore(start) || !now.isAfter(end);
        } else {
            return !now.isBefore(start) && !now.isAfter(end);
        }
    }

    private void triggerEventImmediately(Event event, String slugName) {
        try {
            String jobKey = event.getId() + "_recovery_trigger";

            JobDetail recoveryJob = newJob(EventTriggerJob.class)
                    .withIdentity(jobKey, "recovery")
                    .usingJobData("eventId", event.getId().toString())
                    .usingJobData("slugName", slugName)
                    .usingJobData("type", event.getType().name())
                    .build();

            Trigger immediateTrigger = newTrigger()
                    .withIdentity(jobKey + "_trigger", "recovery")
                    .startNow()
                    .build();

            scheduler.scheduleJob(recoveryJob, immediateTrigger);
            LOGGER.info("Triggered immediate recovery for event {}", event.getId());

        } catch (SchedulerException e) {
            LOGGER.error("Failed to trigger immediate recovery for event {}: {}", event.getId(), e.getMessage());
        }
    }

    private void schedulePeriodicEventWithSlugName(Event event, ZoneId timeZone, Task task, String slugName) throws SchedulerException {
        UUID id = event.getId();
        String jobKey = id + "_event_trigger";
        LocalTime start = LocalTime.parse(task.getPeriodicTrigger().getStartTime());
        LocalTime end = LocalTime.parse(task.getPeriodicTrigger().getEndTime());
        int interval = task.getPeriodicTrigger().getInterval();
        List<String> dows = QuartzUtils.convertWeekdaysToAbbrev(task.getPeriodicTrigger().getWeekdays());

        // Validate interval to prevent infinite loops
        if (interval <= 0) {
            LOGGER.error("Invalid interval {} for event {}", interval, id);
            return;
        }

        LOGGER.info("Scheduling event {} from {} to {} with interval {}m", id, start, end, interval);

        JobDetail job = newJob(EventTriggerJob.class)
                .withIdentity(jobKey, "event")
                .usingJobData("eventId", id.toString())
                .usingJobData("slugName", slugName)
                .usingJobData("type", event.getType().name())
                .build();

        Set<Trigger> triggers = new HashSet<>();
        int triggerCount = 0;
        final int MAX_TRIGGERS = 1000; // Safety limit

        if (end.isBefore(start)) {
            LocalTime t = start;
            while (!t.isAfter(LocalTime.of(23, 59)) && triggerCount < MAX_TRIGGERS) {
                String cron = buildCronForInstant(t.getHour(), t.getMinute(), dows);
                LOGGER.debug("Creating trigger for time {} with cron: {}", t, cron);

                Trigger trig = newTrigger()
                        .withIdentity(jobKey + "_trg_" + t.toString().replace(":", ""), "event")
                        .withSchedule(cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(timeZone)))
                        .build();
                triggers.add(trig);
                triggerCount++;

                LocalTime nextTime = t.plusMinutes(interval);
                if (nextTime.isBefore(t) || nextTime.equals(t)) {
                    break;
                }
                t = nextTime;
            }

            t = LocalTime.MIDNIGHT;
            while (!t.isAfter(end) && triggerCount < MAX_TRIGGERS) {
                String cron = buildCronForInstant(t.getHour(), t.getMinute(), dows);
                LOGGER.debug("Creating midnight trigger for time {} with cron: {}", t, cron);

                Trigger trig = newTrigger()
                        .withIdentity(jobKey + "_trg_midnight_" + t.toString().replace(":", ""), "event")
                        .withSchedule(cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(timeZone)))
                        .build();
                triggers.add(trig);
                triggerCount++;

                LocalTime nextTime = t.plusMinutes(interval);
                if (nextTime.isBefore(t) || nextTime.equals(t)) {
                    // Overflow protection
                    break;
                }
                t = nextTime;
            }
        } else {
            // Same day schedule: start to end
            LocalTime t = start;
            while (!t.isAfter(end) && triggerCount < MAX_TRIGGERS) {
                String cron = buildCronForInstant(t.getHour(), t.getMinute(), dows);
                LOGGER.debug("Creating same-day trigger for time {} with cron: {}", t, cron);

                Trigger trig = newTrigger()
                        .withIdentity(jobKey + "_trg_" + t.toString().replace(":", ""), "event")
                        .withSchedule(cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(timeZone)))
                        .build();
                triggers.add(trig);
                triggerCount++;

                LocalTime nextTime = t.plusMinutes(interval);
                if (nextTime.isBefore(t) || nextTime.equals(t)) {
                    // Overflow protection
                    break;
                }
                t = nextTime;
            }
        }

        if (triggerCount >= MAX_TRIGGERS) {
            LOGGER.error("Hit maximum trigger limit for event {}. Check interval configuration.", id);
            return;
        }

        if (triggers.isEmpty()) {
            LOGGER.warn("No triggers created for event {} with schedule {} to {}", id, start, end);
            return;
        }

        scheduler.scheduleJob(job, triggers, true);
        LOGGER.info("Scheduled periodic event {} with {} triggers, interval {}m from {} to {}",
                id, triggers.size(), interval, start, end);
    }

}