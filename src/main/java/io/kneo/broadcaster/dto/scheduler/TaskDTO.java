package io.kneo.broadcaster.dto.scheduler;

import io.kneo.broadcaster.model.scheduler.TriggerType;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class TaskDTO {
    private UUID id;
    private TriggerType triggerType;
    private OnceTriggerDTO onceTrigger;
    private TimeWindowTriggerDTO timeWindowTrigger;
    private PeriodicTriggerDTO periodicTrigger;
}