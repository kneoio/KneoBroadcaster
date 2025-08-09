package io.kneo.broadcaster.service.scheduler.quartz;

import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.service.MemoryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class EventTriggerJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventTriggerJob.class);

    @Inject
    MemoryService memoryService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String eventId = context.getJobDetail().getJobDataMap().getString("eventId");
        String brandId = context.getJobDetail().getJobDataMap().getString("brandId");
        String type = context.getJobDetail().getJobDataMap().getString("type");
        String description = context.getJobDetail().getJobDataMap().getString("description");
        String priority = context.getJobDetail().getJobDataMap().getString("priority");
        LOGGER.info("Executing event trigger {} for brand {}", eventId, brandId);
        try {
            String message = type + ": " + description + " [" + priority + "]";
            memoryService.upsert(brandId, MemoryType.EVENT, message).subscribe().with(
                    id -> LOGGER.debug("Memory created with ID: {} for event {}", id, eventId),
                    failure -> LOGGER.error("Failed to create memory for event {}", eventId, failure)
            );
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }
}
