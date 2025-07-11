package io.kneo.broadcaster.dto.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class OnceTriggerDTO {
    private String startTime;
    private String duration;
    private List<String> weekdays;
}