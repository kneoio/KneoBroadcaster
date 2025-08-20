package io.kneo.broadcaster.service.scheduler.quartz;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.service.scheduler.SchedulableRepositoryRegistry;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.quartz.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class QuartzSchedulerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzSchedulerManager.class);

    @Inject
    SchedulableRepositoryRegistry repositoryRegistry;

    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @Inject
    Scheduler scheduler;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Starting Quartz scheduler manager");

        // Use a simple timer to delay initialization
        java.util.Timer timer = new java.util.Timer("QuartzSchedulerInit", true);
        timer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                if (initialized.compareAndSet(false, true)) {
                    LOGGER.info("Delayed scheduler initialization starting...");
                    initializeSchedulesWithRetry();
                }
            }
        }, 5000); // 5 second delay
    }

    private void initializeSchedulesWithRetry() {
        LOGGER.info("Checking repository readiness...");
        var repositories = repositoryRegistry.getRepositories();
        LOGGER.info("Found {} repositories: {}", repositories.size(),
                repositories.stream().map(r -> r.getClass().getSimpleName()).toList());

        if (repositories.isEmpty()) {
            LOGGER.warn("No repositories found yet, will retry on next scheduled run");
            initialized.set(false); // Allow retry
            return;
        }

        initializeSchedules();
    }

    private void initializeSchedules() {
        repositoryRegistry.getRepositories().forEach(repository -> {
            LOGGER.info("Initializing schedules from repository: {}", repository.getClass().getSimpleName());
            try {
                repository.findActiveScheduled()
                        .onItem().transformToMulti(Multi.createFrom()::iterable)
                        .onItem().invoke(entity -> {
                            LOGGER.debug("Processing entity: {} ({})", entity.getClass().getSimpleName(), entity);
                            scheduleEntity(entity);
                        })
                        .collect().asList()
                        .subscribe().with(
                                results -> LOGGER.info("Initialized {} schedules from {}",
                                        results.size(), repository.getClass().getSimpleName()),
                                throwable -> LOGGER.error("Failed to initialize schedules from repository: {}",
                                        repository.getClass().getSimpleName(), throwable)
                        );
            } catch (Exception e) {
                LOGGER.error("Exception during repository processing: {}", repository.getClass().getSimpleName(), e);
            }
        });
    }

    public void scheduleEntity(Schedulable entity) {
        try {
            quartzSchedulerService.scheduleEntity(entity);
            LOGGER.debug("Scheduled entity: {}", entity.getClass().getSimpleName());
        } catch (Exception e) {
            LOGGER.error("Failed to schedule entity: {}", entity.getClass().getSimpleName(), e);
        }
    }

    public void removeScheduleForEntity(Schedulable entity) {
        quartzSchedulerService.removeForEntity(entity);
        LOGGER.debug("Removed schedule for entity: {}", entity.getClass().getSimpleName());
    }

    public void refreshSchedules() {
        LOGGER.info("Refreshing all schedules");
        initializeSchedules();
    }

    public void reconcileAll() {
        repositoryRegistry.getRepositories().forEach(repository -> {
            try {
                repository.findActiveScheduled()
                        .onItem().transformToMulti(Multi.createFrom()::iterable)
                        .onItem().invoke(entity -> quartzSchedulerService.reconcileEntity((Schedulable) entity))
                        .collect().asList()
                        .subscribe().with(
                                results -> LOGGER.debug("Reconciled {} entities from {}", results.size(), repository.getClass().getSimpleName()),
                                throwable -> LOGGER.error("Failed to reconcile entities from repository: {}", repository.getClass().getSimpleName(), throwable)
                        );
            } catch (Exception e) {
                LOGGER.error("Exception during reconciliation for repository: {}", repository.getClass().getSimpleName(), e);
            }
        });
    }
}