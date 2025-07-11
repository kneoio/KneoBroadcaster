package io.kneo.broadcaster.repository;


import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.smallrye.mutiny.Uni;

import java.util.List;

public interface SchedulableRepository<T extends Schedulable> {
    Uni<List<T>> findActiveScheduled();
}