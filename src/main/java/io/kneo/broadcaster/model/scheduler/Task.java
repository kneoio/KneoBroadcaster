package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class Task {
    private UUID id;
    private TriggerType triggerType;
    private OnceTrigger onceTrigger;
    private TimeWindowTrigger timeWindowTrigger;
    private PeriodicTrigger periodicTrigger;
}