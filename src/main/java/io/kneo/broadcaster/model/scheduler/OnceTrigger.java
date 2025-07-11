package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Setter
@Getter
public class OnceTrigger {
    private String startTime;
    private String duration;
    private List<String> weekdays;
}