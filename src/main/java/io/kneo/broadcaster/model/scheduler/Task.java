package io.kneo.broadcaster.model.scheduler;

import io.kneo.broadcaster.service.scheduler.ScheduledTaskType;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class Task {
    private UUID id;
    private ScheduledTaskType type;
    private String target;
    private TriggerType triggerType;
    private OnceTrigger onceTrigger;
    private TimeWindowTrigger timeWindowTrigger;
    private PeriodicTrigger periodicTrigger;
}