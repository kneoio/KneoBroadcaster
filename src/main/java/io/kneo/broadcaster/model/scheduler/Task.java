package io.kneo.broadcaster.model.scheduler;

import io.kneo.broadcaster.service.scheduler.CronTaskType;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Task {
    private CronTaskType type;
    private String target;
    private TriggerType triggerType;
    private OnceTrigger onceTrigger;
    private TimeWindowTrigger timeWindowTrigger;
    private PeriodicTrigger periodicTrigger;
}