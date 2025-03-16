package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RadioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioService.class);

    @Inject
    RadioStationPool radioStationPool;

    // Keep track of active stations
    private final Map<String, Boolean> activeStations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public Uni<RadioStation> initializeStation(String brand) {
        LOGGER.info("Initializing station for brand: {}", brand);
        return radioStationPool.initializeStation(brand)
                .onItem().invoke(station -> {
                    // Start continuous playback as soon as station is initialized
                    ensureContinuousPlayback(brand);
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to initialize station for brand: {}", brand, failure);
                });
    }

    public Uni<RadioStation> stopStation(String brand) {
        LOGGER.info("Stop brand: {}", brand);
        activeStations.put(brand, false);
        return radioStationPool.stop(brand) // Assuming this method exists
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to stop station for brand: {}", brand, failure);
                });
    }

    public Uni<HLSPlaylist> getPlaylist(String brand) {
        // Ensure station keeps playing
        ensureContinuousPlayback(brand);

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

    // Ensure continuous playback regardless of listeners
    private void ensureContinuousPlayback(String brand) {
        if (activeStations.putIfAbsent(brand, true) == null) {
            LOGGER.info("Starting continuous playback for brand: {}", brand);

            // Schedule periodic segment generation to keep the station active
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!activeStations.getOrDefault(brand, false)) {
                        return;
                    }

                    // This keeps the playlist active
                    radioStationPool.get(brand)
                            .subscribe().with(
                                    station -> {
                                        if (station != null && station.getPlaylist() != null) {
                                            // Just accessing the playlist keeps it alive
                                            station.getPlaylist().generatePlaylist();
                                        }
                                    },
                                    error -> LOGGER.error("Error in continuous playback for {}: {}", brand, error.getMessage())
                            );
                } catch (Exception e) {
                    LOGGER.error("Unexpected error in continuous playback for {}: {}", brand, e.getMessage());
                }
            }, 0, 5, TimeUnit.MINUTES); // Adjust timing based on your segment duration
        }
    }

    // Method to call during application shutdown
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}