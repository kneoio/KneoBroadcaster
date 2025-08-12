package io.kneo.broadcaster.dto.dashboard;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class JobExecutionDTO {
    private String jobName;
    private String action;
    private LocalDateTime scheduledTime;
    private String triggerState;
}
