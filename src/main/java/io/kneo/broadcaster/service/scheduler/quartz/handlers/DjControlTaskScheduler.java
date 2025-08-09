package io.kneo.broadcaster.service.scheduler.quartz.handlers;

import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;
import io.kneo.broadcaster.service.scheduler.CronTaskType;
import io.kneo.broadcaster.service.scheduler.quartz.DjControlJob;
import io.kneo.broadcaster.service.scheduler.quartz.spi.TaskSchedulerHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.ZoneId;

import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

@ApplicationScoped
public class DjControlTaskScheduler implements TaskSchedulerHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DjControlTaskScheduler.class);

    @Inject
    Scheduler scheduler;

    @Override
    public boolean supports(Schedulable entity, Task task) {
        if (!(entity instanceof RadioStation)) return false;
        if (task == null) return false;
        if (task.getType() != CronTaskType.PROCESS_DJ_CONTROL) return false;
        return task.getTriggerType() == TriggerType.TIME_WINDOW && task.getTimeWindowTrigger() != null;
    }

    @Override
    public void schedule(Schedulable entity, Task task, ZoneId timeZone) throws SchedulerException {
        RadioStation station = (RadioStation) entity;
        String stationSlug = station.getSlugName();
        String startTime = task.getTimeWindowTrigger().getStartTime();
        String endTime = task.getTimeWindowTrigger().getEndTime();
        String target = task.getTarget();
        removeFor(entity);
        scheduleStartJob(stationSlug, startTime, target, timeZone);
        scheduleStopJob(stationSlug, endTime, timeZone);
        scheduleWarningJob(stationSlug, endTime, timeZone);
        LOGGER.info("Scheduled DJ control for station {} from {} to {}", stationSlug, startTime, endTime);
    }

    @Override
    public void removeFor(Schedulable entity) throws SchedulerException {
        if (!(entity instanceof RadioStation station)) return;
        String slug = station.getSlugName();
        scheduler.deleteJob(JobKey.jobKey(slug + "_dj_start", "dj-control"));
        scheduler.deleteJob(JobKey.jobKey(slug + "_dj_stop", "dj-control"));
        scheduler.deleteJob(JobKey.jobKey(slug + "_dj_warning", "dj-control"));
    }

    private void scheduleStartJob(String stationSlug, String startTime, String target, ZoneId timeZone) throws SchedulerException {
        String jobKey = stationSlug + "_dj_start";
        String cronExpression = convertTimeToCron(startTime);
        JobDetail job = newJob(DjControlJob.class)
                .withIdentity(jobKey, "dj-control")
                .usingJobData("stationSlugName", stationSlug)
                .usingJobData("action", "START")
                .usingJobData("target", target)
                .build();
        Trigger trigger = newTrigger()
                .withIdentity(jobKey + "_trigger", "dj-control")
                .withSchedule(cronSchedule(cronExpression).inTimeZone(java.util.TimeZone.getTimeZone(timeZone)))
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    private void scheduleStopJob(String stationSlug, String endTime, ZoneId timeZone) throws SchedulerException {
        String jobKey = stationSlug + "_dj_stop";
        String cronExpression = convertTimeToCron(endTime);
        JobDetail job = newJob(DjControlJob.class)
                .withIdentity(jobKey, "dj-control")
                .usingJobData("stationSlugName", stationSlug)
                .usingJobData("action", "STOP")
                .build();
        Trigger trigger = newTrigger()
                .withIdentity(jobKey + "_trigger", "dj-control")
                .withSchedule(cronSchedule(cronExpression).inTimeZone(java.util.TimeZone.getTimeZone(timeZone)))
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    private void scheduleWarningJob(String stationSlug, String endTime, ZoneId timeZone) throws SchedulerException {
        LocalTime end = LocalTime.parse(endTime);
        LocalTime warning = end.minusMinutes(7);
        String jobKey = stationSlug + "_dj_warning";
        String cronExpression = convertTimeToCron(warning.toString());
        JobDetail job = newJob(DjControlJob.class)
                .withIdentity(jobKey, "dj-control")
                .usingJobData("stationSlugName", stationSlug)
                .usingJobData("action", "WARNING")
                .build();
        Trigger trigger = newTrigger()
                .withIdentity(jobKey + "_trigger", "dj-control")
                .withSchedule(cronSchedule(cronExpression).inTimeZone(java.util.TimeZone.getTimeZone(timeZone)))
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    private String convertTimeToCron(String time) {
        LocalTime localTime = LocalTime.parse(time);
        return String.format("0 %d %d * * ?", localTime.getMinute(), localTime.getHour());
    }
}
