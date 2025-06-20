package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class Schedule {
    private String id;
    private String timezone;
    private List<Task> tasks;

}
