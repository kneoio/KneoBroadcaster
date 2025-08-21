package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.model.BroadcastingStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.soundfragment.BrandSoundFragmentUpdateService;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RadioStationPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationPool.class);
    private final ConcurrentHashMap<String, RadioStation> pool = new ConcurrentHashMap<>();

    @Inject
    RadioStationService radioStationService;

    @Inject
    BroadcasterConfig broadcasterConfig;

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

    @Inject
    MemoryService memoryService;

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
                                            broadcasterConfig,
                                            config,
                                            soundFragmentService,
                                            segmentationService,
                                            10,
                                            updateService
                                    );
                                    stationFromDb.setPlaylist(newPlaylist);
                                    newPlaylist.setRadioStation(stationFromDb);
                                    newPlaylist.initialize();

                                    LOGGER.info("RadioStationPool: StreamManager for {} instance created and StreamManager.initialize() called. Status should be WARMING_UP/WAITING.", key);
                                    return stationFromDb;
                                });
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.error("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Uni<RadioStation> updateStationConfig(String brandName, RadioStation updatedStation) {
        LOGGER.info("Hot-updating configuration for station: {}", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transform(bn -> {
                    RadioStation currentStation = pool.get(bn);
                    if (currentStation == null) {
                        LOGGER.warn("Station {} not found in pool during config update", bn);
                        return null;
                    }

                    RadioStationStatus currentStatus = currentStation.getStatus();
                    AiAgentStatus currentAiStatus = currentStation.getAiAgentStatus();
                    List<RadioStation.StatusChangeRecord> currentHistory = currentStation.getStatusHistory();
                    boolean currentAiControlAllowed = currentStation.isAiControlAllowed();
                    IStreamManager currentPlaylist = currentStation.getPlaylist();

                    updatePersistentFields(currentStation, updatedStation);

                    currentStation.setStatus(currentStatus);
                    currentStation.setAiAgentStatus(currentAiStatus);
                    currentStation.setStatusHistory(currentHistory);
                    currentStation.setAiControlAllowed(currentAiControlAllowed);
                    currentStation.setPlaylist(currentPlaylist);

                    if (currentPlaylist != null) {
                        currentPlaylist.setRadioStation(currentStation);
                    }

                    LOGGER.info("Station {} configuration updated without downtime", bn);
                    return currentStation;
                });
    }

    private void updatePersistentFields(RadioStation current, RadioStation updated) {
        current.setLocalizedName(updated.getLocalizedName());
        current.setSlugName(updated.getSlugName());
        current.setTimeZone(updated.getTimeZone());
        current.setArchived(updated.getArchived());
        current.setCountry(updated.getCountry());
        current.setBitRate(updated.getBitRate());
        current.setManagedBy(updated.getManagedBy());
        current.setColor(updated.getColor());
        current.setDescription(updated.getDescription());
        current.setScheduler(updated.getScheduler());
        current.setAiAgentId(updated.getAiAgentId());
        current.setProfileId(updated.getProfileId());
        current.setLabelList(updated.getLabelList());
        current.setAuthor(updated.getAuthor());
        current.setLastModifier(updated.getLastModifier());
        current.setRegDate(updated.getRegDate());
        current.setLastModifiedDate(updated.getLastModifiedDate());
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

            LOGGER.info("Resetting memory for station: {}", brandName);
            memoryService.deleteByBrand(brandName)
                    .subscribe().with(
                            deletedCount -> LOGGER.info("Successfully deleted {} memory entries for station {}", deletedCount, brandName),
                            failure -> LOGGER.error("Failed to reset memory for station {}: {}", brandName, failure.getMessage(), failure)
                    );

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

    public Uni<BroadcastingStats> getLiveStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        RadioStation radioStation = pool.get(name);
        if (radioStation != null) {
            stats.setStatus(radioStation.getStatus());
            stats.setAiControlAllowed(radioStation.isAiControlAllowed());
        } else {
            stats.setStatus(RadioStationStatus.OFF_LINE);
            stats.setAiControlAllowed(false);
        }
        return Uni.createFrom().item(stats);
    }
}