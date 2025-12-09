package io.kneo.broadcaster.service.scheduler.job;

import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.repository.EventRepository;
import io.kneo.broadcaster.service.scheduler.EventExecutor;
import io.kneo.broadcaster.service.scheduler.EventScheduleEvaluator;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@ApplicationScoped
public class EventTriggerJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventTriggerJob.class);

    @Inject
    EventRepository eventRepository;

    @Inject
    EventScheduleEvaluator evaluator;

    @Inject
    EventExecutor executor;

    @Scheduled(cron = "0 * * * * ?")
    public void execute() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        LOGGER.debug("Event trigger tick at {}", now);

        eventRepository.findActiveScheduled()
                .subscribe().with(
                        events -> processEvents(events, now),
                        error -> LOGGER.error("Failed to fetch scheduled events", error)
                );
    }

    private void processEvents(List<Event> events, ZonedDateTime now) {
        for (Event event : events) {
            if (evaluator.shouldFireNow(event.getScheduler(), now)) {
                executor.execute(event)
                        .subscribe().with(
                                v -> LOGGER.info("Event {} executed successfully", event.getId()),
                                error -> LOGGER.error("Failed to execute event {}", event.getId(), error)
                        );
            }
        }
    }
}