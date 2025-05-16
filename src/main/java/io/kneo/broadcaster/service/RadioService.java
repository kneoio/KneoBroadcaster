package io.kneo.broadcaster.service;

import io.kneo.broadcaster.controller.stream.IStreamManager;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.repository.RadioStationRepository;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.stream.RadioStationPool;
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

    @Inject
    RadioStationRepository radioStationRepository;

    public Uni<RadioStation> initializeStation(String brand) {
        LOGGER.info("Initializing station for brand: {}", brand);
        return radioStationPool.initializeStation(brand)
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to initialize station for brand: {}", brand, failure)
                );
    }

    public Uni<RadioStation> slide(String brand) {
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

    public Uni<IStreamManager> getPlaylist(String brand, String userAgent) {
        return recordAccess(brand, userAgent)
                .onFailure().recoverWithItem(() -> {
                    LOGGER.warn("Failed to record access, but continuing with playlist retrieval: {}", brand);
                    return null;
                })
                .chain(() -> radioStationPool.get(brand))
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE)
                )
                .onItem().transform(RadioStation::getPlaylist)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.PLAYLIST_NOT_AVAILABLE)
                );
    }

    public Uni<Void> recordAccess(String brand, String userAgent) {
        return radioStationRepository.upsertStationAccess(brand, userAgent)
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to record access for brand: {}, userAgent: {}", brand, userAgent, failure)
                );
    }
}