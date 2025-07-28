package io.kneo.broadcaster.service.scheduler;

import java.util.Collection;

public interface TaskTracker {
    Collection<TaskState> getCurrentTasks();
    Collection<TaskState> getTasksForBrand(String brand);
    void resetTasksForBrand(String brand);

}
