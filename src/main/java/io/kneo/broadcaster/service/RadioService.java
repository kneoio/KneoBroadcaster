package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.RadioStation;
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

    public Uni<HLSPlaylist> getPlaylist(String brand) {
        return radioStationPool.get(brand)
                .onItem().transform(station -> {
                    if (station == null || station.getPlaylist() == null || station.getPlaylist().getSegmentCount() == 0) {
                        LOGGER.info("Station not initialized for brand: {}, initializing now", brand);
                        throw new IllegalStateException("Station not initialized");
                    }
                    return station.getPlaylist();
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.info("Recovering from failure by initializing station for brand: {}", brand);
                    return initializeStation(brand)
                            .onItem().transform(RadioStation::getPlaylist);
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to get playlist for brand: {}", brand, failure);
                });
    }
}