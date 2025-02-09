package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.repository.RadioStationRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RadioStationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationService.class);

    @Inject
    RadioStationRepository repository;

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    public Uni<RadioStation> createRadioStation(RadioStation station) {
        return repository.insert(station);
    }

    public Uni<RadioStation> updateRadioStation(UUID id, RadioStation station) {
        return repository.update(id, station);
    }

    public Uni<RadioStation> getRadioStation(UUID id) {
        return repository.findById(id);
    }

    public Uni<List<RadioStation>> getAllRadioStations(int limit, int offset) {
        return repository.getAll(limit, offset);
    }

    public Uni<Integer> deleteRadioStation(UUID id) {
        return repository.delete(id);
    }


}
