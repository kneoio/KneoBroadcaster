package io.kneo.broadcaster.service.scheduler.runners;

import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import org.quartz.SchedulerException;

import java.time.ZoneId;

public interface TaskSchedulerHandler {
    boolean supports(Schedulable entity, Task task);
    void schedule(Schedulable entity, Task task, ZoneId timeZone) throws SchedulerException;
    void removeFor(Schedulable entity) throws SchedulerException;
}
