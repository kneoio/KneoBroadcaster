package io.kneo.broadcaster.dto.scheduler;

import io.kneo.broadcaster.model.scheduler.TriggerType;
import io.kneo.broadcaster.service.scheduler.CronTaskType;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class TaskDTO {
    private UUID id;
    private CronTaskType type;
    private String target;
    private TriggerType triggerType;
    private OnceTriggerDTO onceTrigger;
    private TimeWindowTriggerDTO timeWindowTrigger;
    private PeriodicTriggerDTO periodicTrigger;
}