package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.stats.BroadcastingStats;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.service.manipulation.segmentation.AudioSegmentationService;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.BrandSoundFragmentUpdateService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RadioStationPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationPool.class);
    private final ConcurrentHashMap<String, Brand> pool = new ConcurrentHashMap<>();

    @Inject
    BrandService brandService;

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
    AiHelperService aiHelperService;

    @Inject
    private SongSupplier songSupplier;

    public Uni<Brand> initializeStation(String brandName) {
        LOGGER.info("Attempting to initialize station for brand: {}", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(bn -> {
                    Brand stationAlreadyActive = pool.get(bn);
                    if (stationAlreadyActive != null &&
                            (stationAlreadyActive.getStatus() == RadioStationStatus.ON_LINE ||
                                    stationAlreadyActive.getStatus() == RadioStationStatus.WARMING_UP)) {
                        LOGGER.info("Station {} already active (status: {}) or warming up. Returning existing instance from initial check.", bn, stationAlreadyActive.getStatus());
                        return Uni.createFrom().item(stationAlreadyActive);
                    }

                    return brandService.getBySlugName(bn)
                            .onItem().transformToUni(stationFromDb -> {
                                if (stationFromDb == null) {
                                    LOGGER.warn("Station with brandName {} not found in database. Cannot initialize.", bn);
                                    Brand staleStation = pool.remove(bn);
                                    if (staleStation != null && staleStation.getStreamManager() != null) {
                                        LOGGER.info("Shutting down playlist for stale pooled station {} (not found in DB).", bn);
                                        staleStation.getStreamManager().shutdown();
                                    }
                                    return Uni.createFrom().failure(new RuntimeException("Station not found in DB: " + bn));
                                }

                                Brand finalStationToUse = pool.compute(bn, (key, currentInPool) -> {
                                    if (currentInPool != null &&
                                            (currentInPool.getStatus() == RadioStationStatus.ON_LINE ||
                                                    currentInPool.getStatus() == RadioStationStatus.WARMING_UP)) {
                                        LOGGER.info("Station {} was concurrently initialized and is active in pool. Using that instance.", key);
                                        return currentInPool;
                                    }

                                    if (currentInPool != null && currentInPool.getStreamManager() != null) {
                                        LOGGER.info("Shutting down playlist of existing non-active station {} (status: {}) before replacing.", key, currentInPool.getStatus());
                                        currentInPool.getStreamManager().shutdown();
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
                                            songSupplier,
                                            updateService,
                                            aiHelperService
                                    );
                                    stationFromDb.setStreamManager(newPlaylist);
                                    newPlaylist.setBrand(stationFromDb);
                                    newPlaylist.initialize();

                                    LOGGER.info("RadioStationPool: StreamManager for {} instance created and StreamManager.initialize() called. Status should be WARMING_UP/WAITING.", key);
                                    return stationFromDb;
                                });
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.error("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Uni<Brand> updateStationConfig(String brandName, Brand updatedStation) {
        LOGGER.info("Hot-updating configuration for station: {}", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transform(bn -> {
                    Brand currentStation = pool.get(bn);
                    if (currentStation == null) {
                        LOGGER.warn("Station {} not found in pool during config update", bn);
                        return null;
                    }

                    RadioStationStatus currentStatus = currentStation.getStatus();
                    AiAgentStatus currentAiStatus = currentStation.getAiAgentStatus();
                    List<Brand.StatusChangeRecord> currentHistory = currentStation.getStatusHistory();
                    IStreamManager currentPlaylist = currentStation.getStreamManager();

                    updatePersistentFields(currentStation, updatedStation);

                    currentStation.setStatus(currentStatus);
                    currentStation.setAiAgentStatus(currentAiStatus);
                    currentStation.setStatusHistory(currentHistory);
                    currentStation.setStreamManager(currentPlaylist);

                    if (currentPlaylist != null) {
                        currentPlaylist.setBrand(currentStation);
                    }

                    LOGGER.info("Station {} configuration updated without downtime", bn);
                    return currentStation;
                });
    }

    private void updatePersistentFields(Brand doc, Brand updated) {
        doc.setLocalizedName(updated.getLocalizedName());
        doc.setSlugName(updated.getSlugName());
        doc.setTimeZone(updated.getTimeZone());
        doc.setArchived(updated.getArchived());
        doc.setCountry(updated.getCountry());
        doc.setBitRate(updated.getBitRate());
        doc.setManagedBy(updated.getManagedBy());
        doc.setColor(updated.getColor());
        doc.setDescription(updated.getDescription());
        doc.setAiAgentId(updated.getAiAgentId());
        doc.setProfileId(updated.getProfileId());
        doc.setLabelList(updated.getLabelList());
        doc.setAuthor(updated.getAuthor());
        doc.setLastModifier(updated.getLastModifier());
        doc.setRegDate(updated.getRegDate());
        doc.setLastModifiedDate(updated.getLastModifiedDate());
    }

    public Uni<Brand> get(String brandName) {
        Brand brand = pool.get(brandName);
        return Uni.createFrom().item(brand);
    }

    public Uni<Brand> stopAndRemove(String brandName) {
        LOGGER.info("Attempting to stop and remove station: {}", brandName);
        Brand brand = pool.remove(brandName);

        if (brand != null) {
            LOGGER.info("Station {} found in pool and removed. Shutting down its playlist.", brandName);
            if (brand.getStreamManager() != null) {
                brand.getStreamManager().shutdown();
            }
            brand.setStatus(RadioStationStatus.OFF_LINE);
            return Uni.createFrom().item(brand);
        } else {
            LOGGER.warn("Station {} not found in pool during stopAndRemove.", brandName);
            return Uni.createFrom().nullItem();
        }
    }

    public Collection<Brand> getOnlineStationsSnapshot() {
        return new ArrayList<>(pool.values());
    }

    public Set<String> getActiveSlugNamesSnapshot() {
        return new HashSet<>(pool.keySet());
    }

    public Optional<Brand> getStation(String slugName) {
        return Optional.ofNullable(pool.get(slugName));
    }

    public Uni<BroadcastingStats> getLiveStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        Brand brand = pool.get(name);
        if (brand != null) {
            stats.setStatus(brand.getStatus());
        } else {
            stats.setStatus(RadioStationStatus.OFF_LINE);
            stats.setAiControlAllowed(false);
        }
        return Uni.createFrom().item(stats);
    }
}