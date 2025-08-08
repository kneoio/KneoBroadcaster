package io.kneo.broadcaster.service.scheduler;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class TaskExecutorRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutorRegistry.class);
    private final Map<CronTaskType, TaskExecutor> executors = new HashMap<>();

    @Inject
    Instance<TaskExecutor> taskExecutors;

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Initializing task executor registry");
        taskExecutors.forEach(this::registerExecutor);
        LOGGER.info("Registered {} task executors", executors.size());
        
        executors.forEach((taskType, executor) -> 
            LOGGER.info("  {}: {}", taskType, executor.getClass().getSimpleName()));
    }

    private void registerExecutor(TaskExecutor executor) {
        for (CronTaskType taskType : CronTaskType.values()) {
            if (executor.supports(taskType)) {
                executors.put(taskType, executor);
                LOGGER.debug("Registered executor {} for task type {}", 
                           executor.getClass().getSimpleName(), taskType);
            }
        }
    }

    public TaskExecutor getExecutor(CronTaskType taskType) {
        return executors.get(taskType);
    }

    public void cleanupTasksForEntity(Object entity) {
        executors.values().forEach(executor -> executor.cleanupTasksForEntity(entity));
    }
}
