package io.kneo.broadcaster.model.scheduler;

import java.util.UUID;

public interface Schedulable {
    Schedule getSchedule();
    UUID getId();

}