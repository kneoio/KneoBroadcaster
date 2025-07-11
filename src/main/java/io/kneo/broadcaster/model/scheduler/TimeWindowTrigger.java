package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Setter
@Getter
public class TimeWindowTrigger {
    private String startTime;
    private String endTime;
    private List<String> weekdays;
}