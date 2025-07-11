package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Task {
    private String type;
    private String target;
    private TriggerType triggerType;
    private OnceTrigger onceTrigger;
    private TimeWindowTrigger timeWindowTrigger;
    private PeriodicTrigger periodicTrigger;
}