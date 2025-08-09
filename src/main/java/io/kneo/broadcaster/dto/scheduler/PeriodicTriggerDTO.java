package io.kneo.broadcaster.dto.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class PeriodicTriggerDTO {
    private String startTime;
    private String endTime;
    private int interval;
    private List<String> weekdays;
}