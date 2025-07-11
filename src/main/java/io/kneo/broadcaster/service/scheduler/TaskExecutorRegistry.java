package io.kneo.broadcaster.service.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import jakarta.annotation.PostConstruct;

@ApplicationScoped
public class TaskExecutorRegistry {
    private final Map<String, TaskExecutor> executors = new HashMap<>();

    @Inject
    Instance<TaskExecutor> taskExecutors;

    @PostConstruct
    void init() {
        taskExecutors.forEach(this::registerExecutor);
    }

    private void registerExecutor(TaskExecutor executor) {
        // This is a simplified version - you might want to use annotations
        // or a getSupportedTypes() method on TaskExecutor interface
        if (executor instanceof RadioStreamTaskExecutor) {
            executors.put("START_STREAM", executor);
            executors.put("STOP_STREAM", executor);
        }
        // Add other executor type mappings here
    }

    public TaskExecutor getExecutor(String taskType) {
        return executors.get(taskType);
    }
}
