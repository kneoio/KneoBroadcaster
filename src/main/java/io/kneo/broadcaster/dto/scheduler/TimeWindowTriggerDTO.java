package io.kneo.broadcaster.dto.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class TimeWindowTriggerDTO {
    private String startTime;
    private String endTime;
    private List<String> weekdays;
}