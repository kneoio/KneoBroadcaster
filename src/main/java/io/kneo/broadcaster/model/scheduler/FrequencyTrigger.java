package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class FrequencyTrigger {
    private String start;
    private String end;
    private String type;
    private String timeOfDay;
    private List<String> weekdays;

}
