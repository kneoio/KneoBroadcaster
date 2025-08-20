package io.kneo.broadcaster.service.scheduler.ticker;

import io.kneo.broadcaster.service.scheduler.quartz.QuartzSchedulerManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WindowReconcileTicker {
    @Inject
    QuartzSchedulerManager quartzSchedulerManager;

    @Scheduled(every = "2m")
    void tick() {
        quartzSchedulerManager.reconcileAll();
    }
}
