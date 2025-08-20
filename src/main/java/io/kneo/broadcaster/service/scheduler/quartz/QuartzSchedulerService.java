package io.kneo.broadcaster.service.scheduler.quartz;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Scheduler;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.service.scheduler.runners.TaskSchedulerHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.List;

@ApplicationScoped
public class QuartzSchedulerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzSchedulerService.class);

    @Inject
    Instance<TaskSchedulerHandler> handlers;

    public void scheduleEntity(Schedulable entity) {
        Scheduler schedule = entity.getScheduler();
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
                        handler.reconcile(entity, task, tz);
                    } catch (SchedulerException e) {
                        LOGGER.error("Failed to schedule task for entity: {}", entity.getClass().getSimpleName(), e);
                    }
                    break;
                }
            }
        }
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
