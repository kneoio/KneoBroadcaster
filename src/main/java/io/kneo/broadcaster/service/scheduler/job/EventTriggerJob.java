package io.kneo.broadcaster.service.scheduler.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.service.MemoryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@ApplicationScoped
public class EventTriggerJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventTriggerJob.class);

    @Inject
    MemoryService memoryService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String eventId = context.getJobDetail().getJobDataMap().getString("eventId");
        String slugName = context.getJobDetail().getJobDataMap().getString("slugName");
        String type = context.getJobDetail().getJobDataMap().getString("type");

        LOGGER.info("Executing event trigger {} for brand {}", eventId, slugName);

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> eventData = Map.of("type", type);
            String message = mapper.writeValueAsString(eventData);

            memoryService.upsert(slugName, MemoryType.EVENT, message).subscribe().with(
                    id -> LOGGER.debug("Memory created with ID: {} for event {}", id, eventId),
                    failure -> LOGGER.error("Failed to create memory for event {}", eventId, failure)
            );
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }
}
