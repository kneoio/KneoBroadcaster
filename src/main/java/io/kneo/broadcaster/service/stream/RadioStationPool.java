package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BroadcastingStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.BrandSoundFragmentUpdateService;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RadioStationPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationPool.class);

    private final ConcurrentHashMap<String, RadioStation> pool = new ConcurrentHashMap<>();

    @Inject
    RadioStationService radioStationService;

    @Inject
    HlsPlaylistConfig config;

    @Inject
    SoundFragmentService soundFragmentService;

    @Inject
    SliderTimer sliderTimer;

    @Inject
    SegmentFeederTimer feederTimer;

    @Inject
    AudioSegmentationService segmentationService;

    @Inject
    BrandSoundFragmentUpdateService updateService;

    public Uni<RadioStation> initializeStation(String brandName) {
        LOGGER.info("Attempting to initialize station for brand: {}", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(bn -> {
                    RadioStation stationAlreadyActive = pool.get(bn);
                    if (stationAlreadyActive != null &&
                            (stationAlreadyActive.getStatus() == RadioStationStatus.ON_LINE ||
                                    stationAlreadyActive.getStatus() == RadioStationStatus.WARMING_UP)) {
                        LOGGER.info("Station {} already active (status: {}) or warming up. Returning existing instance from initial check.", bn, stationAlreadyActive.getStatus());
                        return Uni.createFrom().item(stationAlreadyActive);
                    }

                    return radioStationService.findByBrandName(bn)
                            .onItem().transformToUni(stationFromDb -> {
                                if (stationFromDb == null) {
                                    LOGGER.warn("Station with brandName {} not found in database. Cannot initialize.", bn);
                                    RadioStation staleStation = pool.remove(bn);
                                    if (staleStation != null && staleStation.getPlaylist() != null) {
                                        LOGGER.info("Shutting down playlist for stale pooled station {} (not found in DB).", bn);
                                        staleStation.getPlaylist().shutdown();
                                    }
                                    return Uni.createFrom().failure(new RuntimeException("Station not found in DB: " + bn));
                                }

                                RadioStation finalStationToUse = pool.compute(bn, (key, currentInPool) -> {
                                    if (currentInPool != null &&
                                            (currentInPool.getStatus() == RadioStationStatus.ON_LINE ||
                                                    currentInPool.getStatus() == RadioStationStatus.WARMING_UP)) {
                                        LOGGER.info("Station {} was concurrently initialized and is active in pool. Using that instance.", key);
                                        return currentInPool;
                                    }

                                    if (currentInPool != null && currentInPool.getPlaylist() != null) {
                                        LOGGER.info("Shutting down playlist of existing non-active station {} (status: {}) before replacing.", key, currentInPool.getStatus());
                                        currentInPool.getPlaylist().shutdown();
                                    }

                                    LOGGER.info("RadioStationPool: Creating new StreamManager instance for station {}.", key);
                                    feederTimer.setDurationSec(config.getSegmentDuration());
                                    StreamManager newPlaylist = new StreamManager(
                                            sliderTimer,
                                            feederTimer,
                                            config,
                                            soundFragmentService,
                                            segmentationService,
                                            10,
                                            updateService
                                    );
                                    stationFromDb.setPlaylist(newPlaylist);

                                    // Set the station reference on the StreamManager *before* calling initialize
                                    newPlaylist.setRadioStation(stationFromDb);

                                    // Now, StreamManager's initialize will set WARMING_UP or WAITING_FOR_CURATOR
                                    newPlaylist.initialize();

                                    LOGGER.info("RadioStationPool: StreamManager for {} instance created and StreamManager.initialize() called. Status should be WARMING_UP/WAITING.", key);
                                    return stationFromDb;
                                });
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.error("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Uni<Void> feedStation(String brandName) {
        LOGGER.info("Attempting to feed {} station", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(bn -> {
                    RadioStation station = pool.get(bn);
                    if (station != null &&
                            (station.getStatus() == RadioStationStatus.ON_LINE ||
                                    station.getStatus() == RadioStationStatus.WARMING_UP)) {
                        StreamManager playlists = (StreamManager) station.getPlaylist();
                        playlists.feedSegments();
                        return Uni.createFrom().voidItem();
                    }
                    return Uni.createFrom().voidItem();
                })
                .onFailure().invoke(failure ->
                        LOGGER.error("Overall failure to feed station {}: {}", brandName,
                                failure.getMessage(), failure));
    }

    public Uni<RadioStation> get(String brandName) {
        RadioStation radioStation = pool.get(brandName);
        return Uni.createFrom().item(radioStation);
    }

    public Uni<RadioStation> stopAndRemove(String brandName) {
        LOGGER.info("Attempting to stop and remove station: {}", brandName);
        RadioStation radioStation = pool.remove(brandName);

        if (radioStation != null) {
            LOGGER.info("Station {} found in pool and removed. Shutting down its playlist.", brandName);
            if (radioStation.getPlaylist() != null) {
                radioStation.getPlaylist().shutdown();
            }
            radioStation.setStatus(RadioStationStatus.OFF_LINE);
            // Consider if station status needs to be updated in the database here
            return Uni.createFrom().item(radioStation);
        } else {
            LOGGER.warn("Station {} not found in pool during stopAndRemove.", brandName);
            return Uni.createFrom().nullItem();
        }
    }

    public Collection<RadioStation> getOnlineStationsSnapshot() {
        return new ArrayList<>(pool.values());
    }

    public Optional<RadioStation> getStation(String slugName) {
        return Optional.ofNullable(pool.get(slugName));
    }

    public Uni<RadioStation> stop(String brandName) {
        LOGGER.info("Attempting to stop station: {}", brandName);
        RadioStation radioStation = pool.get(brandName);
        if (radioStation == null) {
            LOGGER.warn("Attempted to stop station {} not found in active pool.", brandName);
            return Uni.createFrom().nullItem();
        }

        if (radioStation.getPlaylist() != null &&
                (radioStation.getStatus() == RadioStationStatus.ON_LINE || radioStation.getStatus() == RadioStationStatus.WARMING_UP)) {
            LOGGER.info("Shutting down playlist for station {}.", brandName);
            radioStation.getPlaylist().shutdown();
        }
        radioStation.setStatus(RadioStationStatus.OFF_LINE);
        LOGGER.info("Station {} status set to OFF_LINE. It remains in the pool in this state.", brandName);
        return Uni.createFrom().item(radioStation);
    }

    public Uni<BroadcastingStats> checkStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        RadioStation radioStation = pool.get(name);
        if (radioStation != null) {
            stats.setStatus(radioStation.getStatus());
        } else {
            stats.setStatus(RadioStationStatus.OFF_LINE);
        }
        return Uni.createFrom().item(stats);
    }
}