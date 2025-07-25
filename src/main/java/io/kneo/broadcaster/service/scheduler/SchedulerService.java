package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Task;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@ApplicationScoped
public class SchedulerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerService.class);
    private static final Duration CHECK_INTERVAL = Duration.ofMinutes(1);
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(30);

    @Inject
    SchedulableRepositoryRegistry repositoryRegistry;

    @Inject
    TaskExecutorRegistry taskExecutorRegistry;

    private Cancellable schedulerSubscription;

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Starting scheduler service with {} second intervals", CHECK_INTERVAL.getSeconds());
        startScheduler();
    }

    private void startScheduler() {
        schedulerSubscription = getTicker()
                .onItem().invoke(this::processSchedules)
                .onFailure().invoke(error -> LOGGER.error("Scheduler execution failed", error))
                .subscribe().with(
                        item -> {},
                        failure -> LOGGER.error("Scheduler subscription failed", failure)
                );
    }

    private Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(CHECK_INTERVAL)
                .onOverflow().drop();
    }

    public void stopScheduler() {
        if (schedulerSubscription != null) {
            schedulerSubscription.cancel();
        }
    }

    private void processSchedules(Long tick) {
        LOGGER.debug("Processing schedules at tick: {}", tick);

        repositoryRegistry.getRepositories().forEach(repository ->
                repository.findActiveScheduled()
                        .onItem().transformToMulti(Multi.createFrom()::iterable)
                        .onItem().call(this::processEntitySchedule)
                        .collect().asList()
                        .subscribe().with(
                                results -> LOGGER.debug("Processed {} scheduled entities", results.size()),
                                throwable -> LOGGER.error("Failed to process schedules", throwable)
                        )
        );
    }

    private Uni<Void> processEntitySchedule(Schedulable entity) {
        if (!entity.getSchedule().isEnabled()) {
            return Uni.createFrom().voidItem();
        }

        LocalDateTime now = LocalDateTime.now(entity.getSchedule().getTimeZone());
        String currentTime = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        String currentDay = now.getDayOfWeek().name();

        List<Task> dueTasks = entity.getSchedule().getTasks().stream()
                .filter(task -> isTaskDue(task, currentTime, currentDay, now))
                .toList();

        if (dueTasks.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        LOGGER.info("Found {} due tasks for entity: {}", dueTasks.size(), entity.getId());

        return Multi.createFrom().iterable(dueTasks)
                .onItem().call(task -> executeTask(entity, task, currentTime))
                .collect().asList()
                .replaceWithVoid();
    }

    private boolean isTaskDue(Task task, String currentTime, String currentDay, LocalDateTime now) {
        if (hasWeekdayFilter(task) && !isCurrentDayIncluded(task, currentDay)) {
            return false;
        }

        return switch (task.getTriggerType()) {
            case ONCE -> isOnceTriggerDue(task, currentTime, currentDay);
            case TIME_WINDOW -> isTimeWindowTriggerDue(task, currentTime);
            case PERIODIC -> isPeriodicTriggerDue(task, currentTime, now);
        };
    }

    private boolean hasWeekdayFilter(Task task) {
        return switch (task.getTriggerType()) {
            case ONCE -> task.getOnceTrigger() != null &&
                    task.getOnceTrigger().getWeekdays() != null &&
                    !task.getOnceTrigger().getWeekdays().isEmpty();
            case TIME_WINDOW -> task.getTimeWindowTrigger() != null &&
                    task.getTimeWindowTrigger().getWeekdays() != null &&
                    !task.getTimeWindowTrigger().getWeekdays().isEmpty();
            case PERIODIC -> task.getPeriodicTrigger() != null &&
                    task.getPeriodicTrigger().getWeekdays() != null &&
                    !task.getPeriodicTrigger().getWeekdays().isEmpty();
        };
    }

    private boolean isCurrentDayIncluded(Task task, String currentDay) {
        List<String> weekdays = switch (task.getTriggerType()) {
            case ONCE -> task.getOnceTrigger().getWeekdays();
            case TIME_WINDOW -> task.getTimeWindowTrigger().getWeekdays();
            case PERIODIC -> task.getPeriodicTrigger().getWeekdays();
        };

        return weekdays != null && weekdays.contains(currentDay);
    }

    private boolean isOnceTriggerDue(Task task, String currentTime, String currentDay) {
        if (task.getOnceTrigger() == null) return false;
        return task.getOnceTrigger().getStartTime().equals(currentTime);
    }

    private boolean isTimeWindowTriggerDue(Task task, String currentTime) {
        if (task.getTimeWindowTrigger() == null) return false;

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime start = LocalTime.parse(task.getTimeWindowTrigger().getStartTime());
        LocalTime end = LocalTime.parse(task.getTimeWindowTrigger().getEndTime());

        return !current.isBefore(start) && !current.isAfter(end);
    }

    private boolean isPeriodicTriggerDue(Task task, String currentTime, LocalDateTime now) {
        if (task.getPeriodicTrigger() == null) return false;

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime start = LocalTime.parse(task.getPeriodicTrigger().getStartTime());
        LocalTime end = LocalTime.parse(task.getPeriodicTrigger().getEndTime());

        if (current.isBefore(start) || current.isAfter(end)) {
            return false;
        }

        String interval = task.getPeriodicTrigger().getInterval();
        int intervalMinutes = parseInterval(interval);

        int minutesSinceStart = (int) Duration.between(start.atDate(now.toLocalDate()), now).toMinutes();
        return minutesSinceStart % intervalMinutes == 0;
    }

    private int parseInterval(String interval) {
        if (interval.endsWith("m")) {
            return Integer.parseInt(interval.substring(0, interval.length() - 1));
        } else if (interval.endsWith("h")) {
            return Integer.parseInt(interval.substring(0, interval.length() - 1)) * 60;
        }
        return 30;
    }

    private Uni<Void> executeTask(Schedulable entity, Task task, String currentTime) {
        ScheduleExecutionContext context = new ScheduleExecutionContext(entity, task, currentTime);

        TaskExecutor executor = taskExecutorRegistry.getExecutor(task.getType());
        if (executor == null) {
            LOGGER.warn("No executor found for task type: {}", task.getType());
            return Uni.createFrom().voidItem();
        }

        LOGGER.info("Executing task: {} for entity: {} at time: {}",
                task.getType(), entity.getId(), currentTime);

        return executor.execute(context)
                .onFailure().invoke(throwable ->
                        LOGGER.error("Failed to execute task: {} for entity: {}",
                                task.getType(), entity.getId(), throwable));
    }
}