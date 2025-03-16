package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RadioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioService.class);

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    public Uni<RadioStation> initializeStation(String brand) {
        LOGGER.info("Initializing station for brand: {}", brand);
        return radioStationPool.initializeStation(brand)
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to initialize station for brand: {}", brand, failure);
                });
    }

    public Uni<RadioStation> stopStation(String brand) {
        LOGGER.info("Stop brand: {}", brand);
        return radioStationPool.initializeStation(brand)
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to initialize station for brand: {}", brand, failure);
                });
    }

    public Uni<HLSPlaylist> getPlaylist(String brand) {
        return radioStationPool.get(brand)
                .onItem().transform(station -> {
                    if (station == null || station.getPlaylist() == null || station.getPlaylist().getSegmentCount() == 0) {
                        LOGGER.warn("Station not initialized for brand: {}", brand);
                        throw new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE);
                    }
                    return station.getPlaylist();
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to get playlist for brand: {}", brand, failure);
                });
    }
}