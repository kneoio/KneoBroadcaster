package io.kneo.broadcaster.model.stats;

import lombok.Data;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
public class SchedulerTaskTimeline {
    private Map<String, Task> tasks = new HashMap<>();

    @Data
    public static class Task {
        private final String id;
        private final String name;
        private final String schedulerName;
        private final Instant startTime;
        private final long intervalSeconds;
        private int executionCount = 0;
        private Instant lastExecutionTime;
        private Instant nextExecutionTime;

        public Task(String id, String name, String schedulerName, long intervalSeconds) {
            this.id = id;
            this.name = name;
            this.schedulerName = schedulerName;
            this.startTime = Instant.now();
            this.lastExecutionTime = startTime;
            this.intervalSeconds = intervalSeconds;
            this.nextExecutionTime = startTime.plusSeconds(intervalSeconds);
        }

        /**
         * Get current progress percentage towards next execution
         * @return Value between 0-100 representing progress
         */
        public double getCurrentProgress() {
            if (lastExecutionTime == null) return 0;

            Instant now = Instant.now();

            // If we've passed next execution time, show 100%
            if (now.isAfter(nextExecutionTime)) return 100;

            // Calculate how far we are between last execution and next execution
            long totalDurationMillis = Duration.between(lastExecutionTime, nextExecutionTime).toMillis();
            long elapsedMillis = Duration.between(lastExecutionTime, now).toMillis();

            if (totalDurationMillis <= 0) return 0;
            return (elapsedMillis * 100.0) / totalDurationMillis;
        }

        /**
         * Record a task execution
         */
        public void recordExecution() {
            executionCount++;
            lastExecutionTime = Instant.now();
            nextExecutionTime = lastExecutionTime.plusSeconds(intervalSeconds);
        }

        /**
         * Get time remaining until next execution
         * @return Duration until next execution
         */
        public Duration getTimeRemaining() {
            return Duration.between(Instant.now(), nextExecutionTime);
        }
    }

    /**
     * Register a scheduled task
     */
    public Task registerTask(String id, String name, String schedulerName, long intervalSeconds) {
        Task task = new Task(id, name, schedulerName, intervalSeconds);
        tasks.put(id, task);
        return task;
    }

    /**
     * Record execution of a task
     */
    public void recordTaskExecution(String id) {
        Task task = tasks.get(id);
        if (task != null) {
            task.recordExecution();
        }
    }
}