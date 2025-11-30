package io.kneo.broadcaster.service.scheduler.job;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class EventTriggerJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventTriggerJob.class);

    @Scheduled(cron = "0 0 * * * ?")
    public void execute() {
        LOGGER.info("Executing hourly event trigger");
    }
}