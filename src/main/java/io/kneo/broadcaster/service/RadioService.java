package io.kneo.broadcaster.service;

import io.kneo.broadcaster.service.stream.RadioStationPool;
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
    RadioStationPool radioStationPool;

    public Uni<RadioStation> initializeStation(String brand) {
        LOGGER.info("Initializing station for brand: {}", brand);
        return radioStationPool.initializeStation(brand)
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to initialize station for brand: {}", brand, failure)
                );
    }

    public Uni<RadioStation> stopStation(String brand) {
        LOGGER.info("Stop brand: {}", brand);
        return radioStationPool.stop(brand)
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to stop station for brand: {}", brand, failure)
                );
    }

    public Uni<HLSPlaylist> getPlaylist(String brand) {
        return radioStationPool.get(brand)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE)
                )
                .onItem().transform(RadioStation::getPlaylist)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.PLAYLIST_NOT_AVAILABLE)
                )
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to get playlist for brand: {}", brand, failure)
                );
    }

}