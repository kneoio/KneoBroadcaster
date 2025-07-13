package io.kneo.broadcaster.service.scheduler;

import io.smallrye.mutiny.Uni;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractTaskExecutor implements TaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTaskExecutor.class);

    private final Map<String, TaskState> runningTasks = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> execute(ScheduleExecutionContext context) {
        return executeTask(context);
    }

    protected abstract Uni<Void> executeTask(ScheduleExecutionContext context);

    protected void addRunningTask(String taskKey, UUID entityId, String taskType, String target) {
        runningTasks.put(taskKey, new TaskState(entityId, taskType, target, LocalDateTime.now()));
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

    @Getter
    public static class TaskState {
        private final UUID entityId;
        private final String taskType;
        private final String target;
        private final LocalDateTime startTime;

        public TaskState(UUID entityId, String taskType, String target, LocalDateTime startTime) {
            this.entityId = entityId;
            this.taskType = taskType;
            this.target = target;
            this.startTime = startTime;
        }
    }
}