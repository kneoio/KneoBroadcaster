package io.kneo.broadcaster.service.scheduler.quartz;

import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.scheduler.Schedule;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;
import io.kneo.broadcaster.service.scheduler.CronTaskType;
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
public class QuartzSchedulerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzSchedulerService.class);

    @Inject
    Scheduler scheduler;

    public void scheduleRadioStation(RadioStation station) {
        if (station.getSchedule() == null || !station.getSchedule().isEnabled()) {
            removeScheduleForStation(station.getSlugName());
            return;
        }

        Schedule schedule = station.getSchedule();
        List<Task> tasks = schedule.getTasks();
        
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        for (Task task : tasks) {
            if (task.getType() == CronTaskType.PROCESS_DJ_CONTROL) {
                scheduleDjControlTask(station, task, schedule.getTimeZone());
            }
        }
    }

    private void scheduleDjControlTask(RadioStation station, Task task, ZoneId timeZone) {
        String stationSlug = station.getSlugName();
        
        try {
            removeScheduleForStation(stationSlug);
            
            if (task.getTriggerType() == TriggerType.TIME_WINDOW && task.getTimeWindowTrigger() != null) {
                String startTime = task.getTimeWindowTrigger().getStartTime();
                String endTime = task.getTimeWindowTrigger().getEndTime();
                String target = task.getTarget();

                scheduleStartJob(stationSlug, startTime, target, timeZone);
                scheduleStopJob(stationSlug, endTime, timeZone);
                scheduleWarningJob(stationSlug, endTime, timeZone);
                
                LOGGER.info("Scheduled DJ control for station {} from {} to {}", 
                           stationSlug, startTime, endTime);
            }
        } catch (SchedulerException e) {
            LOGGER.error("Failed to schedule DJ control task for station: {}", stationSlug, e);
        }
    }

    private void scheduleStartJob(String stationSlug, String startTime, String target, ZoneId timeZone) 
            throws SchedulerException {
        
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
                .withSchedule(cronSchedule(cronExpression)
                        .inTimeZone(java.util.TimeZone.getTimeZone(timeZone)))
                .build();

        scheduler.scheduleJob(job, trigger);
        LOGGER.debug("Scheduled start job for station {} at {}", stationSlug, startTime);
    }

    private void scheduleStopJob(String stationSlug, String endTime, ZoneId timeZone) 
            throws SchedulerException {
        
        String jobKey = stationSlug + "_dj_stop";
        String cronExpression = convertTimeToCron(endTime);
        
        JobDetail job = newJob(DjControlJob.class)
                .withIdentity(jobKey, "dj-control")
                .usingJobData("stationSlugName", stationSlug)
                .usingJobData("action", "STOP")
                .build();

        Trigger trigger = newTrigger()
                .withIdentity(jobKey + "_trigger", "dj-control")
                .withSchedule(cronSchedule(cronExpression)
                        .inTimeZone(java.util.TimeZone.getTimeZone(timeZone)))
                .build();

        scheduler.scheduleJob(job, trigger);
        LOGGER.debug("Scheduled stop job for station {} at {}", stationSlug, endTime);
    }

    private void scheduleWarningJob(String stationSlug, String endTime, ZoneId timeZone) 
            throws SchedulerException {
        
        LocalTime end = LocalTime.parse(endTime);
        LocalTime warning = end.minusMinutes(7);
        String warningTime = warning.toString();
        
        String jobKey = stationSlug + "_dj_warning";
        String cronExpression = convertTimeToCron(warningTime);
        
        JobDetail job = newJob(DjControlJob.class)
                .withIdentity(jobKey, "dj-control")
                .usingJobData("stationSlugName", stationSlug)
                .usingJobData("action", "WARNING")
                .build();

        Trigger trigger = newTrigger()
                .withIdentity(jobKey + "_trigger", "dj-control")
                .withSchedule(cronSchedule(cronExpression)
                        .inTimeZone(java.util.TimeZone.getTimeZone(timeZone)))
                .build();

        scheduler.scheduleJob(job, trigger);
        LOGGER.debug("Scheduled warning job for station {} at {}", stationSlug, warningTime);
    }

    public void removeScheduleForStation(String stationSlug) {
        try {
            boolean startDeleted = scheduler.deleteJob(JobKey.jobKey(stationSlug + "_dj_start", "dj-control"));
            boolean stopDeleted = scheduler.deleteJob(JobKey.jobKey(stationSlug + "_dj_stop", "dj-control"));
            boolean warningDeleted = scheduler.deleteJob(JobKey.jobKey(stationSlug + "_dj_warning", "dj-control"));
            
            if (startDeleted || stopDeleted || warningDeleted) {
                LOGGER.info("Removed scheduled jobs for station: {}", stationSlug);
            }
        } catch (SchedulerException e) {
            LOGGER.error("Failed to remove scheduled jobs for station: {}", stationSlug, e);
        }
    }

    private String convertTimeToCron(String time) {
        LocalTime localTime = LocalTime.parse(time);
        int hour = localTime.getHour();
        int minute = localTime.getMinute();
        
        return String.format("0 %d %d * * ?", minute, hour);
    }
}
