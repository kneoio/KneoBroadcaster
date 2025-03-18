package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.ReactiveHlsPlaylist;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.stream.HlsTimerService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing radio stations with reactive HLS playlists
 * Ensures continuous streaming regardless of active listeners
 */
@ApplicationScoped
public class ReactiveRadioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveRadioService.class);

    @Inject
    private RadioStationPool radioStationPool;

    @Inject
    private HlsTimerService timerService;

    @Inject
    private ReactiveAudioSegmentationService audioSegmentationService;

    @Inject
    private SoundFragmentService soundFragmentService;

    @Inject
    private HlsPlaylistConfig hlsConfig;

    // Playlist scheduler is responsible for tracking played items across stations
    @Inject
    private io.kneo.broadcaster.service.radio.PlaylistScheduler playlistScheduler;

    // Track active stations
    private final Map<String, ReactiveHlsPlaylist> activePlaylists = new ConcurrentHashMap<>();

    /**
     * Initialize a radio station for the given brand
     * This will create and start a continuous HLS stream
     */
    public Uni<RadioStation> initializeStation(String brand) {
        LOGGER.info("Initializing station for brand: {}", brand);

        // Check if station is already active
        if (activePlaylists.containsKey(brand)) {
            LOGGER.info("Station already active for brand: {}", brand);
            return radioStationPool.get(brand);
        }

        // Create a new reactive playlist for this station
        ReactiveHlsPlaylist playlist = new ReactiveHlsPlaylist(
                timerService,
                hlsConfig,
                audioSegmentationService,
                soundFragmentService,
                playlistScheduler,
                brand
        );

        // Initialize the playlist (starts continuous segment generation)
        playlist.initialize();

        // Register the playlist
        activePlaylists.put(brand, playlist);

        // Update the radio station in the pool
        return radioStationPool.initializeStation(brand)
                .onItem().transform(station -> {
                    // Replace the old playlist with our reactive one
                    if (station.getPlaylist() != null) {
                        station.getPlaylist().shutdown();
                    }
                    station.setPlaylist(playlist);
                    return station;
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to initialize station for brand: {}", brand, failure);
                    cleanup(brand);
                });
    }

    /**
     * Stop a station and clean up resources
     */
    public Uni<RadioStation> stopStation(String brand) {
        LOGGER.info("Stopping station for brand: {}", brand);

        cleanup(brand);

        return radioStationPool.stop(brand)
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to stop station for brand: {}", brand, failure);
                });
    }

    /**
     * Get the playlist for a station, ensuring it's initialized
     */
    public Uni<ReactiveHlsPlaylist> getPlaylist(String brand) {
        // Get the playlist directly if it's active
        ReactiveHlsPlaylist playlist = activePlaylists.get(brand);
        if (playlist != null) {
            return Uni.createFrom().item(playlist);
        }

        // Try to get from the station pool and initialize if needed
        return radioStationPool.get(brand)
                .onItem().transformToUni(station -> {
                    if (station == null) {
                        LOGGER.warn("Station not found for brand: {}", brand);
                        return Uni.createFrom().failure(
                                new RadioStationException(RadioStationException.ErrorType.STATION_NOT_FOUND)
                        );
                    }

                    // Check if station has our reactive playlist
                    if (station.getPlaylist() instanceof ReactiveHlsPlaylist) {
                        ReactiveHlsPlaylist existingPlaylist = (ReactiveHlsPlaylist) station.getPlaylist();
                        activePlaylists.putIfAbsent(brand, existingPlaylist);
                        return Uni.createFrom().item(existingPlaylist);
                    }

                    // Initialize if not active
                    LOGGER.info("Station found but needs initialization for brand: {}", brand);
                    return initializeStation(brand)
                            .onItem().transform(initializedStation -> {
                                if (initializedStation.getPlaylist() instanceof ReactiveHlsPlaylist) {
                                    return (ReactiveHlsPlaylist) initializedStation.getPlaylist();
                                }

                                LOGGER.error("Failed to get reactive playlist for brand: {}", brand);
                                throw new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE);
                            });
                });
    }

    /**
     * Clean up resources for a station
     */
    private void cleanup(String brand) {
        ReactiveHlsPlaylist playlist = activePlaylists.remove(brand);
        if (playlist != null) {
            playlist.shutdown();
        }
    }

    /**
     * Shut down all active stations
     */
    public void shutdown() {
        LOGGER.info("Shutting down all radio stations");

        // Make a copy to avoid concurrent modification
        new HashMap<>(activePlaylists).forEach((brand, playlist) -> {
            try {
                playlist.shutdown();
            } catch (Exception e) {
                LOGGER.error("Error shutting down playlist for brand: {}", brand, e);
            }
        });

        activePlaylists.clear();
    }
}