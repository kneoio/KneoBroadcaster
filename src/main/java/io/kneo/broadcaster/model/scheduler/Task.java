package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class Task {
    private String id;
    private String type;
    private TimeTrigger timeTrigger;
    private Trigger trigger;
    private String duration;
    private List<String> weekdays;

}
