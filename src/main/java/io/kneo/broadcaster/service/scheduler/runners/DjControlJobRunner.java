package io.kneo.broadcaster.service.scheduler.runners;

import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;
import io.kneo.broadcaster.service.scheduler.ScheduledTaskType;
import io.kneo.broadcaster.service.scheduler.job.DjControlJob;
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
import java.time.ZonedDateTime;
import java.time.DayOfWeek;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static io.kneo.broadcaster.service.scheduler.quartz.QuartzUtils.convertWeekdaysToCron;
import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

@ApplicationScoped
public class DjControlJobRunner implements JobRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DjControlJobRunner.class);

    @Inject
    Scheduler scheduler;

    @Override
    public boolean supports(Schedulable entity, Task task) {
        if (!(entity instanceof RadioStation)) return false;
        if (task == null) return false;
        if (task.getType() != ScheduledTaskType.PROCESS_DJ_CONTROL) return false;
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
    public void reconcile(Schedulable entity, Task task, ZoneId timeZone) throws SchedulerException {
        if (!(entity instanceof RadioStation)) return;
        if (task == null || task.getTriggerType() != TriggerType.TIME_WINDOW || task.getTimeWindowTrigger() == null) return;
        String stationSlug = ((RadioStation) entity).getSlugName();
        String startTime = task.getTimeWindowTrigger().getStartTime();
        String endTime = task.getTimeWindowTrigger().getEndTime();
        List<String> weekdays = task.getTimeWindowTrigger().getWeekdays();
        reconcileWindowNow(stationSlug, startTime, endTime, timeZone, weekdays);
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
        String cronExpression = QuartzUtils.convertTimeToCron(startTime, weekdays);
        JobDetail job = newJob(DjControlJob.class)
                .withIdentity(jobKey, "dj-control")
                .usingJobData("stationSlugName", stationSlug)
                .usingJobData("action", "START")
                .usingJobData("target", target)
                .build();
        Trigger trigger = newTrigger()
                .withIdentity(jobKey + "_trigger", "dj-control")
                .withSchedule(cronSchedule(cronExpression)
                        .inTimeZone(java.util.TimeZone.getTimeZone(timeZone))
                        .withMisfireHandlingInstructionFireAndProceed())
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
                .withSchedule(cronSchedule(cronExpression)
                        .inTimeZone(java.util.TimeZone.getTimeZone(timeZone))
                        .withMisfireHandlingInstructionFireAndProceed())
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
                .withSchedule(cronSchedule(cronExpression)
                        .inTimeZone(java.util.TimeZone.getTimeZone(timeZone))
                        .withMisfireHandlingInstructionDoNothing())
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    private String convertTimeToCron(String time, List<String> weekdays) {
        LocalTime localTime = LocalTime.parse(time);
        String dayOfWeek = convertWeekdaysToCron(weekdays);
        return String.format("0 %d %d ? * %s", localTime.getMinute(), localTime.getHour(), dayOfWeek);
    }

    private void reconcileWindowNow(String stationSlug, String startTime, String endTime, ZoneId timeZone, List<String> weekdays) throws SchedulerException {
        if (!isTodayInWeekdays(weekdays, timeZone)) return;
        ZonedDateTime now = ZonedDateTime.now(timeZone);
        LocalTime nowTime = now.toLocalTime();
        LocalTime start = LocalTime.parse(startTime);
        LocalTime end = LocalTime.parse(endTime);
        if (!wrapsMidnight(start, end)) {
            if (!nowTime.isBefore(start) && nowTime.isBefore(end)) {
                LOGGER.info("Reconcile DJ start for {}", stationSlug);
                scheduler.triggerJob(JobKey.jobKey(stationSlug + "_dj_start", "dj-control"));
                return;
            }
            if (!nowTime.isBefore(end)) {
                LOGGER.info("Reconcile DJ stop for {}", stationSlug);
                scheduler.triggerJob(JobKey.jobKey(stationSlug + "_dj_stop", "dj-control"));
                return;
            }
        } else {
            boolean inWindow = !nowTime.isBefore(start) || nowTime.isBefore(end);
            if (inWindow) {
                LOGGER.info("Reconcile DJ start (overnight) for {}", stationSlug);
                scheduler.triggerJob(JobKey.jobKey(stationSlug + "_dj_start", "dj-control"));
                return;
            } else {
                LOGGER.info("Reconcile DJ stop (overnight) for {}", stationSlug);
                scheduler.triggerJob(JobKey.jobKey(stationSlug + "_dj_stop", "dj-control"));
                return;
            }
        }
    }

    private boolean isTodayInWeekdays(List<String> weekdays, ZoneId timeZone) {
        if (weekdays == null || weekdays.isEmpty()) return true;
        Set<String> set = expandWeekdays(weekdays);
        String today = toAbbrev(ZonedDateTime.now(timeZone).getDayOfWeek());
        return set.contains(today);
    }

    private Set<String> expandWeekdays(List<String> weekdays) {
        Map<String, Integer> order = weekdayOrder();
        Set<String> result = new LinkedHashSet<>();
        for (String w : weekdays) {
            String s = normalizeWeekToken(w.trim());
            if (s.contains("-")) {
                String[] parts = s.split("-");
                String from = normalizeWeekToken(parts[0].trim());
                String to = normalizeWeekToken(parts[1].trim());
                Integer i1 = order.get(from);
                Integer i2 = order.get(to);
                if (i1 != null && i2 != null) {
                    if (i1 <= i2) {
                        order.entrySet().stream().filter(e -> e.getValue() >= i1 && e.getValue() <= i2).forEach(e -> result.add(e.getKey()));
                    } else {
                        order.entrySet().stream().filter(e -> e.getValue() >= i1 || e.getValue() <= i2).forEach(e -> result.add(e.getKey()));
                    }
                }
            } else {
                result.add(s);
            }
        }
        return result;
    }

    private String normalizeWeekToken(String token) {
        String t = token.toUpperCase();
        return switch (t) {
            case "MONDAY" -> "MON";
            case "TUESDAY" -> "TUE";
            case "WEDNESDAY" -> "WED";
            case "THURSDAY" -> "THU";
            case "FRIDAY" -> "FRI";
            case "SATURDAY" -> "SAT";
            case "SUNDAY" -> "SUN";
            default -> t;
        };
    }

    private Map<String, Integer> weekdayOrder() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("MON", 1);
        m.put("TUE", 2);
        m.put("WED", 3);
        m.put("THU", 4);
        m.put("FRI", 5);
        m.put("SAT", 6);
        m.put("SUN", 7);
        return m;
    }

    private String toAbbrev(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "MON";
            case TUESDAY -> "TUE";
            case WEDNESDAY -> "WED";
            case THURSDAY -> "THU";
            case FRIDAY -> "FRI";
            case SATURDAY -> "SAT";
            case SUNDAY -> "SUN";
        };
    }

    private boolean wrapsMidnight(LocalTime start, LocalTime end) {
        return end.isBefore(start);
    }
}
