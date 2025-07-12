package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.repository.RadioStationRepository;
import io.kneo.broadcaster.repository.SchedulableRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SchedulableRepositoryRegistry {
    @Getter
    private final List<SchedulableRepository<? extends Schedulable>> repositories = new ArrayList<>();

    @Inject
    RadioStationRepository radioStationRepository;

    void onStart(@Observes StartupEvent event) {
        repositories.add(radioStationRepository);
    }

}