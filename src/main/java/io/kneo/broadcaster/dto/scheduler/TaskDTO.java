package io.kneo.broadcaster.dto.scheduler;

import io.kneo.broadcaster.model.scheduler.TriggerType;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class TaskDTO {
    private String type;
    private String target;
    private TriggerType triggerType;
    private OnceTriggerDTO onceTrigger;
    private TimeWindowTriggerDTO timeWindowTrigger;
    private PeriodicTriggerDTO periodicTrigger;
}