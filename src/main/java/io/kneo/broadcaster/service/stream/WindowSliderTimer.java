package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.Multi;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, AtomicReference<ScheduledFuture<?>>> currentTasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> nextSlideDelays = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Long>> tickConsumers = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, ZonedDateTime> scheduledTimes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> totalSlides = new ConcurrentHashMap<>();
    private final Map<String, Long> fixedIntervalsBatrd = new ConcurrentHashMap<>();

    public Multi<Long> getSliderTicker(String brand) {
        return Multi.createFrom().emitter(emitter -> {
            LOGGER.info("New slider subscriber connected for brand: {}", brand);

            tickConsumers.computeIfAbsent(brand, k -> tick -> {
                totalSlides.computeIfAbsent(brand, b -> new AtomicLong(0)).incrementAndGet();
                emitter.emit(tick);
            });

            scheduleNextSlide(brand);

            emitter.onTermination(() -> {
                LOGGER.warn("Subscriber disconnected for brand: {}. Cleaning up...", brand);
                cancelCurrentTask(brand);
                tickConsumers.remove(brand);
                fixedIntervalsBatrd.remove(brand);
            });
        });
    }

    private void scheduleNextSlide(String brand) {
        cancelCurrentTask(brand);

        long delay = nextSlideDelays.computeIfAbsent(brand, k -> new AtomicLong(30_000)).get();
        ZoneId zone = ZoneId.of("Europe/Lisbon");
        ZonedDateTime scheduled = ZonedDateTime.now(zone).plusNanos(delay * 1_000_000L);
        scheduledTimes.put(brand, scheduled);

        LOGGER.info("Scheduling next slide for brand {} in {}ms (at {} local time)",
                brand,
                delay,
                scheduled.format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        ScheduledFuture<?> newTask = scheduler.schedule(() -> {
            ZonedDateTime actualTime = ZonedDateTime.now(zone);
            LOGGER.info("Slide triggered for brand {}! Scheduled: {}, Actual: {}, Drift: {}ms",
                    brand,
                    scheduledTimes.get(brand).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                    actualTime.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                    ChronoUnit.MILLIS.between(scheduledTimes.get(brand), actualTime));

            Consumer<Long> consumer = tickConsumers.get(brand);
            if (consumer != null) {
                consumer.accept(System.currentTimeMillis());
            }
        }, delay, TimeUnit.MILLISECONDS);

        currentTasks.put(brand, new AtomicReference<>(newTask));
    }

    public void updateSlideDelay(String brand, long delayMillis) {
        if (delayMillis > 0) {
            AtomicLong currentDelay = nextSlideDelays.get(brand);

            currentDelay.set(delayMillis);

            AtomicReference<ScheduledFuture<?>> currentTaskRef = currentTasks.get(brand);
            if (currentTaskRef != null && currentTaskRef.get() != null) {
                LOGGER.info("Rescheduling for brand {} with new delay", brand);
                scheduleNextSlide(brand);
            }
        }
    }

    private void cancelCurrentTask(String brand) {
        AtomicReference<ScheduledFuture<?>> taskRef = currentTasks.get(brand);
        if (taskRef != null) {
            ScheduledFuture<?> task = taskRef.getAndSet(null);
            if (task != null) {
                task.cancel(false);
                LOGGER.debug("Cancelled pending slide task for brand {}", brand);
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        LOGGER.info("Shutting down WindowSliderService...");
        currentTasks.forEach((brand, taskRef) -> {
            ScheduledFuture<?> task = taskRef.getAndSet(null);
            if (task != null) {
                task.cancel(false);
                LOGGER.debug("Cancelled pending slide task for brand {} during shutdown", brand);
            }
        });
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                LOGGER.warn("Force shutdown after timeout");
            }
        } catch (InterruptedException e) {
            LOGGER.error("Shutdown interrupted", e);
            Thread.currentThread().interrupt();
        }
        long total = totalSlides.values().stream().mapToLong(AtomicLong::get).sum();
        LOGGER.info("Shutdown complete. Total slides across all brands: {}", total);
    }

    public ZonedDateTime getScheduledTime(String brand) {
        return scheduledTimes.get(brand);
    }

    public void setFixedSlideInterval(String brand, long intervalMillis) {
        if (intervalMillis > 0) {
            fixedIntervalsBatrd.put(brand, intervalMillis);
            cancelCurrentTask(brand);
            ZoneId zone = ZoneId.of("Europe/Lisbon");
            ZonedDateTime scheduled = ZonedDateTime.now(zone).plusNanos(intervalMillis * 1_000_000L);
            scheduledTimes.put(brand, scheduled);

            ScheduledFuture<?> newTask = scheduler.scheduleAtFixedRate(() -> {
                ZonedDateTime actualTime = ZonedDateTime.now(zone);
                LOGGER.info("Fixed interval slide triggered for brand {}! Scheduled (approx): {}, Actual: {}, Drift (approx): {}ms",
                        brand,
                        scheduledTimes.get(brand).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                        actualTime.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                        ChronoUnit.MILLIS.between(scheduledTimes.get(brand), actualTime));
                Consumer<Long> consumer = tickConsumers.get(brand);
                if (consumer != null) {
                    consumer.accept(System.currentTimeMillis());
                }
                scheduledTimes.put(brand, ZonedDateTime.now(zone).plusNanos(intervalMillis * 1_000_000L));
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
            currentTasks.put(brand, new AtomicReference<>(newTask));
        } else {
            fixedIntervalsBatrd.remove(brand);
            cancelCurrentTask(brand);
            // Optionally, you could reschedule with a default dynamic delay here
            // scheduleNextSlide(brand);
        }
    }
}