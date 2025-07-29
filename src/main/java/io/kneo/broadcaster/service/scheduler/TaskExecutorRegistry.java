package io.kneo.broadcaster.service.scheduler;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class TaskExecutorRegistry {
    private final Map<CronTaskType, TaskExecutor> executors = new HashMap<>();

    @Inject
    Instance<TaskExecutor> taskExecutors;

    void onStart(@Observes StartupEvent event) {
        taskExecutors.forEach(this::registerExecutor);
    }

    private void registerExecutor(TaskExecutor executor) {
        if (executor instanceof BrandScheduledTaskExecutor) {
            executors.put(CronTaskType.PROCESS_DJ_CONTROL, executor);
        }
    }

    public TaskExecutor getExecutor(CronTaskType taskType) {
        return executors.get(taskType);
    }

    public void cleanupTasksForEntity(Object entity) {
        executors.values().forEach(executor -> executor.cleanupTasksForEntity(entity));
    }
}
