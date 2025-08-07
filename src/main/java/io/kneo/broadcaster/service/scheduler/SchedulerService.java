package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;
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
                        failure -> {
                            LOGGER.error("Scheduler subscription failed, restarting", failure);
                            restartScheduler();
                        }
                );
    }

    private void restartScheduler() {
        stopScheduler();
        startScheduler();
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
            schedulerSubscription = null;
        }
    }

    private void processSchedules(Long tick) {
        //LOGGER.info("=== SCHEDULER TICK {} ===", tick);
        //LOGGER.info("Available repositories: {}", repositoryRegistry.getRepositories().size());

        repositoryRegistry.getRepositories().forEach(repository -> {
            LOGGER.info("Processing repository: {}", repository.getClass().getSimpleName());
            repository.findActiveScheduled()
                    .onItem().transformToMulti(Multi.createFrom()::iterable)
                    .onItem().call(this::processEntitySchedule)
                    .collect().asList()
                    .subscribe().with(
                            results -> LOGGER.debug("Processed {} scheduled entities", results.size()),
                            throwable -> LOGGER.error("Failed to process schedules from repository", throwable)
                    );
        });
    }

    private Uni<Void> processEntitySchedule(Schedulable entity) {
        if (!entity.getSchedule().isEnabled()) {
            taskExecutorRegistry.cleanupTasksForEntity(entity);
            return Uni.createFrom().voidItem();
        }

        LocalDateTime now = LocalDateTime.now(entity.getSchedule().getTimeZone());
        String currentTime = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        String currentDay = now.getDayOfWeek().name();

        List<Task> tasks = entity.getSchedule().getTasks();
        if (tasks == null) {
            return Uni.createFrom().voidItem();
        }

        List<Task> dueTasks = tasks.stream()
                .filter(task -> isTaskDue(task, currentTime, currentDay, now))
                .toList();

        if (dueTasks.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Multi.createFrom().iterable(dueTasks)
                .onItem().call(task -> executeTask(entity, task, currentTime))
                .collect().asList()
                .replaceWithVoid();
    }

    private boolean isTaskDue(Task task, String currentTime, String currentDay, LocalDateTime now) {
        if (!WeekdayFilter.isAllowed(task, currentDay)) {
            return false;
        }

        if (task.getTriggerType() == TriggerType.ONCE) {
            return isOnceTriggerDue(task, currentTime);
        } else if (task.getTriggerType() == TriggerType.TIME_WINDOW) {
            return isTimeWindowTriggerDue(task, currentTime);
        } else if (task.getTriggerType() == TriggerType.PERIODIC) {
            return isPeriodicTriggerDue(task, currentTime, now);
        }

        return false;
    }

    private boolean isOnceTriggerDue(Task task, String currentTime) {
        if (task.getOnceTrigger() == null) {
            return false;
        }
        return TimeUtils.isAtTime(currentTime, task.getOnceTrigger().getStartTime());
    }

    private boolean isTimeWindowTriggerDue(Task task, String currentTime) {
        if (task.getTimeWindowTrigger() == null) {
            return false;
        }
        return TimeUtils.isWithinWindow(currentTime,
                task.getTimeWindowTrigger().getStartTime(),
                task.getTimeWindowTrigger().getEndTime());
    }

    private boolean isPeriodicTriggerDue(Task task, String currentTime, LocalDateTime now) {
        if (task.getPeriodicTrigger() == null) {
            return false;
        }

        if (!TimeUtils.isWithinWindow(currentTime,
                task.getPeriodicTrigger().getStartTime(),
                task.getPeriodicTrigger().getEndTime())) {
            return false;
        }

        return true;
    }

    private Uni<Void> executeTask(Schedulable entity, Task task, String currentTime) {
        ScheduleExecutionContext context = new ScheduleExecutionContext(entity, task, currentTime);

        TaskExecutor executor = taskExecutorRegistry.getExecutor(task.getType());
        if (executor == null) {
            LOGGER.warn("No executor found for task type: {}", task.getType());
            return Uni.createFrom().voidItem();
        }

        return executor.execute(context)
                .onFailure().invoke(throwable ->
                        LOGGER.error("Failed to execute task: {} for entity: {}",
                                task.getType(), entity.getId(), throwable)
                );
    }
}