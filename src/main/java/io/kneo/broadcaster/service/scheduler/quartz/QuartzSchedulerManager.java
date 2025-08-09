package io.kneo.broadcaster.service.scheduler.quartz;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.service.scheduler.SchedulableRepositoryRegistry;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class QuartzSchedulerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzSchedulerManager.class);

    @Inject
    SchedulableRepositoryRegistry repositoryRegistry;

    @Inject
    QuartzSchedulerService quartzSchedulerService;

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Starting Quartz scheduler manager");
        initializeSchedules();
    }

    private void initializeSchedules() {
        repositoryRegistry.getRepositories().forEach(repository -> {
            LOGGER.info("Initializing schedules from repository: {}", repository.getClass().getSimpleName());
            repository.findActiveScheduled()
                    .onItem().transformToMulti(Multi.createFrom()::iterable)
                    .onItem().invoke(this::scheduleEntity)
                    .collect().asList()
                    .subscribe().with(
                            results -> LOGGER.info("Initialized {} schedules from {}", 
                                                  results.size(), repository.getClass().getSimpleName()),
                            throwable -> LOGGER.error("Failed to initialize schedules from repository", throwable)
                    );
        });
    }

    public void scheduleEntity(Schedulable entity) {
        quartzSchedulerService.scheduleEntity(entity);
        LOGGER.debug("Scheduled entity: {}", entity.getClass().getSimpleName());
    }

    public void removeScheduleForEntity(Schedulable entity) {
        quartzSchedulerService.removeForEntity(entity);
        LOGGER.debug("Removed schedule for entity: {}", entity.getClass().getSimpleName());
    }

    public void refreshSchedules() {
        LOGGER.info("Refreshing all schedules");
        initializeSchedules();
    }
}
