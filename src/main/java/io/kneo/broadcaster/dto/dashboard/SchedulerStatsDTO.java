package io.kneo.broadcaster.dto.dashboard;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class SchedulerStatsDTO {
    private boolean schedulerRunning;
    private String schedulerName;
    private int totalScheduledTasks;
    private int activeJobs;
    private int pausedJobs;
    private int completedJobs;
    private int errorJobs;
    private List<String> jobGroups;
    private List<ScheduledTaskDTO> tasks;
    private LocalDateTime lastUpdated;
}
