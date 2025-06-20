package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class TimeTrigger {
    private Trigger trigger;
    private FrequencyTrigger frequencyTrigger;

}
