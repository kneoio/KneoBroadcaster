package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.repository.RadioStationRepository;
import io.kneo.broadcaster.repository.SchedulableRepository;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
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

    @PostConstruct
    void init() {
        repositories.add(radioStationRepository);
    }

}