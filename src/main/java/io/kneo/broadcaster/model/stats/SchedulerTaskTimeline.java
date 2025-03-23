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
        private int currentProgress;
        private long timeRemaining;

        public Task(String id, String name, long intervalSeconds) {
            this.id = id;
            this.name = name;
            this.startTime = Instant.now();
            this.lastExecutionTime = startTime;
            this.intervalSeconds = intervalSeconds;
            this.nextExecutionTime = startTime.plusSeconds(intervalSeconds);
            this.currentProgress = 0;
            this.timeRemaining = intervalSeconds;
        }

        public void recordExecution() {
            executionCount++;
            lastExecutionTime = Instant.now();
            nextExecutionTime = lastExecutionTime.plusSeconds(intervalSeconds);
            currentProgress = 0;
            timeRemaining = intervalSeconds;
        }
    }

    public void registerTask(String id, String name, long intervalSeconds) {
        task = new Task(id, name, intervalSeconds);
    }

    public void updateProgress() {
        if (task == null) {
            return;
        }

        Instant now = Instant.now();
        long elapsedTime = now.getEpochSecond() - task.getLastExecutionTime().getEpochSecond();
        long remainingTime = task.getIntervalSeconds() - elapsedTime;

        task.setTimeRemaining(Math.max(0, remainingTime));
        int progress = (int) (((double) (task.getIntervalSeconds() - remainingTime) / task.getIntervalSeconds()) * 100);
        task.setCurrentProgress(Math.min(100, Math.max(0, progress)));

        if (task.getCurrentProgress() >= 100) {
            task.recordExecution();
        }
    }

    public void resetProgress() {
        if (task != null) {
            task.setCurrentProgress(0);
            task.setLastExecutionTime(Instant.now());
            task.setNextExecutionTime(task.getLastExecutionTime().plusSeconds(task.getIntervalSeconds()));
            task.setTimeRemaining(task.getIntervalSeconds());
        }
    }
}