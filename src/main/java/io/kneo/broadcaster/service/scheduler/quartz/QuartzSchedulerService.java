package io.kneo.broadcaster.service.scheduler.quartz;

import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.scheduler.Schedule;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.service.scheduler.quartz.spi.TaskSchedulerHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.List;
import jakarta.enterprise.inject.Instance;

@ApplicationScoped
public class QuartzSchedulerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzSchedulerService.class);

    @Inject
    Instance<TaskSchedulerHandler> handlers;

    public void scheduleEntity(Schedulable entity) {
        Schedule schedule = entity.getSchedule();
        if (schedule == null || !schedule.isEnabled()) {
            removeForEntity(entity);
            return;
        }
        List<Task> tasks = schedule.getTasks();
        if (tasks == null || tasks.isEmpty()) return;
        ZoneId tz = schedule.getTimeZone();
        for (Task task : tasks) {
            for (TaskSchedulerHandler handler : handlers) {
                if (handler.supports(entity, task)) {
                    try {
                        handler.schedule(entity, task, tz);
                    } catch (SchedulerException e) {
                        LOGGER.error("Failed to schedule task for entity: {}", entity.getClass().getSimpleName(), e);
                    }
                    break;
                }
            }
        }
    }

    public void scheduleRadioStation(RadioStation station) {
        scheduleEntity(station);
    }
    public void removeScheduleForStation(RadioStation station) {
        removeForEntity(station);
    }

    public void removeForEntity(Schedulable entity) {
        for (TaskSchedulerHandler handler : handlers) {
            try {
                handler.removeFor(entity);
            } catch (SchedulerException e) {
                LOGGER.error("Failed to remove scheduled jobs for entity: {}", entity.getClass().getSimpleName(), e);
            }
        }
    }
}
