package io.kneo.broadcaster.model.scheduler;

import java.util.UUID;

public interface Schedulable {
    Scheduler getScheduler();
    UUID getId();

}