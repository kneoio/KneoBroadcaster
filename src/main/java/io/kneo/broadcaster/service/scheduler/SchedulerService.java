package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.service.scheduler.quartz.QuartzSchedulerManager;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SchedulerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerService.class);

    @Inject
    QuartzSchedulerManager quartzSchedulerManager;

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Scheduler service now using Quartz-based implementation");
    }

    public void scheduleEntity(Schedulable entity) {
        quartzSchedulerManager.scheduleEntity(entity);
    }

    public void removeScheduleForEntity(Schedulable entity) {
        quartzSchedulerManager.removeScheduleForEntity(entity);
    }

    public void refreshSchedules() {
        quartzSchedulerManager.refreshSchedules();
    }
}