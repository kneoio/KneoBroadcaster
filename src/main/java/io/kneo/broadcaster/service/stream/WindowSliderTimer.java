package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.Multi;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@ApplicationScoped
public class WindowSliderTimer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowSliderTimer.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<ScheduledFuture<?>> currentTask = new AtomicReference<>();
    private final AtomicLong nextSlideDelay = new AtomicLong(120_000); //after 120 sec. the first shift will happen
    private volatile Consumer<Long> tickConsumer;

    private final AtomicLong totalSlides = new AtomicLong(0);
    private volatile Instant lastSlideTime;

    public Multi<Long> getSliderTicker() {
        return Multi.createFrom().emitter(emitter -> {
            LOGGER.info("New slider subscriber connected");

            this.tickConsumer = tick -> {
                totalSlides.incrementAndGet();
                lastSlideTime = Instant.now();
                emitter.emit(tick);
            };

            scheduleNextSlide();

            emitter.onTermination(() -> {
                LOGGER.warn("Subscriber disconnected. Cleaning up...");
                cancelCurrentTask();
                this.tickConsumer = null;
            });
        });
    }

    private void scheduleNextSlide() {
        cancelCurrentTask();

        long delay = nextSlideDelay.get();
        ZoneId zone = ZoneId.of("Europe/Lisbon");
        ZonedDateTime scheduledTime = ZonedDateTime.now(zone).plusNanos(delay * 1_000_000L);

        LOGGER.info("Scheduling next slide in {}ms (at {} local time)",
                delay,
                scheduledTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        ScheduledFuture<?> newTask = scheduler.schedule(() -> {
            ZonedDateTime actualTime = ZonedDateTime.now(zone);
            LOGGER.info("Slide triggered! Scheduled: Drift: {}ms",
                    ChronoUnit.MILLIS.between(scheduledTime, actualTime));

            if (tickConsumer != null) {
                tickConsumer.accept(System.currentTimeMillis());
            }
           /// scheduleNextSlide();
        }, delay, TimeUnit.MILLISECONDS);

        currentTask.set(newTask);
    }

    public void updateSlideDelay(long delayMillis) {
        if (delayMillis > 0) {
            LOGGER.warn("Updating slide delay from {}ms to {}ms",
                    nextSlideDelay.get(), delayMillis);
            nextSlideDelay.set(delayMillis);

            if (currentTask.get() != null) {
                LOGGER.info("Rescheduling with new delay");
                scheduleNextSlide();
            }
        }
    }

    private void cancelCurrentTask() {
        ScheduledFuture<?> task = currentTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
            LOGGER.debug("Cancelled pending slide task");
        }
    }

    @PreDestroy
    public void cleanup() {
        LOGGER.info("Shutting down WindowSliderService...");
        cancelCurrentTask();
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                LOGGER.warn("Force shutdown after timeout");
            }
        } catch (InterruptedException e) {
            LOGGER.error("Shutdown interrupted", e);
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Shutdown complete. Total slides: {}", totalSlides.get());
    }
}