package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class Trigger {
    private String type;
    private String start;
    private String haltStop;
    private List<String> weekdays;

}
