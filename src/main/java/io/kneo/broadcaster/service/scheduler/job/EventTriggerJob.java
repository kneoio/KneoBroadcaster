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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class EventTriggerJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventTriggerJob.class);

    private final AtomicReference<LocalDateTime> lastTick = new AtomicReference<>();
    private final AtomicInteger totalEventsChecked = new AtomicInteger(0);
    private final AtomicInteger totalEventsFired = new AtomicInteger(0);
    private final AtomicInteger totalErrors = new AtomicInteger(0);
    private final AtomicReference<String> lastFiredEventId = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastFiredTime = new AtomicReference<>();

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
        lastTick.set(LocalDateTime.now());
        totalEventsChecked.addAndGet(events.size());

        for (Event event : events) {
            if (evaluator.shouldFireNow(event.getScheduler(), now)) {
                totalEventsFired.incrementAndGet();
                lastFiredEventId.set(event.getId().toString());
                lastFiredTime.set(LocalDateTime.now());

                executor.execute(event)
                        .subscribe().with(
                                v -> LOGGER.info("Event {} executed successfully", event.getId()),
                                error -> {
                                    totalErrors.incrementAndGet();
                                    LOGGER.error("Failed to execute event {}", event.getId(), error);
                                }
                        );
            }
        }
    }

    public LocalDateTime getLastTick() {
        return lastTick.get();
    }

    public int getTotalEventsChecked() {
        return totalEventsChecked.get();
    }

    public int getTotalEventsFired() {
        return totalEventsFired.get();
    }

    public int getTotalErrors() {
        return totalErrors.get();
    }

    public String getLastFiredEventId() {
        return lastFiredEventId.get();
    }

    public LocalDateTime getLastFiredTime() {
        return lastFiredTime.get();
    }
}