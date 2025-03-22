package io.kneo.broadcaster.model.stats;

import lombok.Data;

import java.time.Instant;

@Data
public class SchedulerTaskTimeline {
    private Task task;

    @Data
    public static class Task {
        private final String id;
        private final String name;
        private final Instant startTime;
        private final long intervalSeconds;
        private int executionCount = 0;
        private Instant lastExecutionTime;
        private Instant nextExecutionTime;
        private double currentProgress;
        private double timeRemaining;

        public Task(String id, String name, long intervalSeconds) {
            this.id = id;
            this.name = name;
            this.startTime = Instant.now();
            this.lastExecutionTime = startTime;
            this.intervalSeconds = intervalSeconds;
            this.nextExecutionTime = startTime.plusSeconds(intervalSeconds);
        }

        public void recordExecution() {
            executionCount++;
            lastExecutionTime = Instant.now();
            nextExecutionTime = lastExecutionTime.plusSeconds(intervalSeconds);
            // Reset progress to 0 when a new execution happens
            currentProgress = 0;
        }
    }

    public void registerTask(String id, String name, long intervalSeconds) {
        task = new Task(id, name, intervalSeconds);
    }

    public void updateProgress() {
        if (task != null) {
            Instant now = Instant.now();

            // Calculate time remaining in seconds
            long totalTimeInSeconds = task.getIntervalSeconds();
            long elapsedTime = now.getEpochSecond() - task.getLastExecutionTime().getEpochSecond();
            long remainingTime = totalTimeInSeconds - elapsedTime;

            // Ensure we don't go negative
            task.setTimeRemaining(Math.max(0, remainingTime));

            // Calculate progress percentage (0-100)
            double progress = ((double)(totalTimeInSeconds - remainingTime) / totalTimeInSeconds) * 100;
            task.setCurrentProgress(Math.min(100, Math.max(0, progress)));

            // Check if progress has reached 100 and it's time for execution
            if (task.getCurrentProgress() >= 100) {
                // Auto-execute the task and reset
                task.recordExecution();
            }
        }
    }

    // Optional: Add a manual reset method if needed
    public void resetProgress() {
        if (task != null) {
            task.setCurrentProgress(0);
            task.setLastExecutionTime(Instant.now());
            task.setNextExecutionTime(task.getLastExecutionTime().plusSeconds(task.getIntervalSeconds()));
        }
    }
}