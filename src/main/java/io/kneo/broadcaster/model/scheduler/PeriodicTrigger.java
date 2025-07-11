package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Setter
@Getter
public class PeriodicTrigger {
    private String startTime;
    private String endTime;
    private String interval;
    private List<String> weekdays;
}