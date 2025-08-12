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
import java.util.List;

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
        List<String> weekdays = task.getTimeWindowTrigger().getWeekdays();
        removeFor(entity);
        scheduleStartJob(stationSlug, startTime, target, timeZone, weekdays);
        scheduleStopJob(stationSlug, endTime, timeZone, weekdays);
        scheduleWarningJob(stationSlug, endTime, timeZone, weekdays);
        LOGGER.info("Scheduled DJ control for station {} from {} to {} on {}", stationSlug, startTime, endTime, weekdays);
    }

    @Override
    public void removeFor(Schedulable entity) throws SchedulerException {
        if (!(entity instanceof RadioStation station)) return;
        String slug = station.getSlugName();
        scheduler.deleteJob(JobKey.jobKey(slug + "_dj_start", "dj-control"));
        scheduler.deleteJob(JobKey.jobKey(slug + "_dj_stop", "dj-control"));
        scheduler.deleteJob(JobKey.jobKey(slug + "_dj_warning", "dj-control"));
    }

    private void scheduleStartJob(String stationSlug, String startTime, String target, ZoneId timeZone, List<String> weekdays) throws SchedulerException {
        String jobKey = stationSlug + "_dj_start";
        String cronExpression = convertTimeToCron(startTime, weekdays);
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

    private void scheduleStopJob(String stationSlug, String endTime, ZoneId timeZone, List<String> weekdays) throws SchedulerException {
        String jobKey = stationSlug + "_dj_stop";
        String cronExpression = convertTimeToCron(endTime, weekdays);
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

    private void scheduleWarningJob(String stationSlug, String endTime, ZoneId timeZone, List<String> weekdays) throws SchedulerException {
        LocalTime end = LocalTime.parse(endTime);
        LocalTime warning = end.minusMinutes(7);
        String jobKey = stationSlug + "_dj_warning";
        String cronExpression = convertTimeToCron(warning.toString(), weekdays);
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

    private String convertTimeToCron(String time, List<String> weekdays) {
        LocalTime localTime = LocalTime.parse(time);
        String dayOfWeek = convertWeekdaysToCron(weekdays);
        return String.format("0 %d %d ? * %s", localTime.getMinute(), localTime.getHour(), dayOfWeek);
    }

    private String convertWeekdaysToCron(List<String> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            return "*";
        }
        
        return weekdays.stream()
                .map(this::convertWeekdayToCronFormat)
                .reduce((first, second) -> first + "," + second)
                .orElse("*");
    }

    private String convertWeekdayToCronFormat(String weekday) {
        return switch (weekday.toUpperCase()) {
            case "MONDAY" -> "MON";
            case "TUESDAY" -> "TUE";
            case "WEDNESDAY" -> "WED";
            case "THURSDAY" -> "THU";
            case "FRIDAY" -> "FRI";
            case "SATURDAY" -> "SAT";
            case "SUNDAY" -> "SUN";
            default -> weekday.substring(0, 3).toUpperCase();
        };
    }
}
