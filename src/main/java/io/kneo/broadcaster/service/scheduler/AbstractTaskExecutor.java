package io.kneo.broadcaster.service.scheduler;

import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public abstract class AbstractTaskExecutor implements TaskExecutor, TaskTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTaskExecutor.class);

    private final Map<String, TaskState> runningTasks = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> execute(ScheduleExecutionContext context) {
        return executeTask(context);
    }

    protected abstract Uni<Void> executeTask(ScheduleExecutionContext context);

    @Override
    public Collection<TaskState> getCurrentTasks() {
        return new ArrayList<>(runningTasks.values());
    }

    @Override
    public Collection<TaskState> getTasksForBrand(String brand) {
        return runningTasks.values().stream()
                .filter(task -> task.getBrand().equals(brand))
                .collect(Collectors.toList());
    }

    @Override
    public void resetTasksForBrand(String brand) {
        runningTasks.entrySet().removeIf(entry -> {
            String taskKey = entry.getKey();
            TaskState taskState = entry.getValue();
            if (taskState.getBrand() != null && taskState.getBrand().equals(brand)) {
                LOGGER.info("Resetting task for brand: {} (key: {})", brand, taskKey);
                return true;
            }
            return false;
        });
    }

    public void resetTasksForStation(String stationSlugName) {
        runningTasks.entrySet().removeIf(entry -> {
            String taskKey = entry.getKey();
            if (taskKey.contains(stationSlugName)) {
                LOGGER.info("Resetting task for station: {} (key: {})", stationSlugName, taskKey);
                return true;
            }
            return false;
        });
    }

    protected void addRunningTask(String taskKey, UUID entityId, String taskType, String target, String brand) {
        runningTasks.put(taskKey, new TaskState(entityId, taskType, target, brand, LocalDateTime.now()));
    }

    protected void removeRunningTask(String taskKey) {
        runningTasks.remove(taskKey);
    }

    protected TaskState getRunningTask(String taskKey) {
        return runningTasks.get(taskKey);
    }

    protected boolean isTaskRunning(String taskKey) {
        return runningTasks.containsKey(taskKey);
    }


}