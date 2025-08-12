package io.kneo.broadcaster.dto.dashboard;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class ScheduledTaskDTO {
    private String entityId;
    private String entityType;
    private String entityName;
    private String taskType;
    private String triggerType;
    private String status;
    private String timeZone;
    private LocalDateTime nextExecution;
    private LocalDateTime lastExecution;
    private String cronExpression;
    private List<JobExecutionDTO> upcomingExecutions;
    private boolean enabled;
}
