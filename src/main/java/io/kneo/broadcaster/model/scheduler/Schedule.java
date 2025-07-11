package io.kneo.broadcaster.model.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.time.ZoneId;
import java.util.List;

@Setter
@Getter
public class Schedule {
    private boolean isEnabled;
    private ZoneId timeZone;
    private List<Task> tasks;
}