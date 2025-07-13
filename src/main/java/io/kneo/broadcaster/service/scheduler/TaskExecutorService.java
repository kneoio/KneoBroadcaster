package io.kneo.broadcaster.service.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ApplicationScoped
public class TaskExecutorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutorService.class);

    @Inject
    private Instance<AbstractTaskExecutor> taskExecutors;

    public void resetTasksForStation(String stationSlugName) {
        LOGGER.info("Resetting tasks for station: {}", stationSlugName);

        List<AbstractTaskExecutor> executors = taskExecutors.stream().toList();

        for (AbstractTaskExecutor executor : executors) {
            executor.resetTasksForStation(stationSlugName);
        }

        LOGGER.info("Completed task reset for station: {}", stationSlugName);
    }
}