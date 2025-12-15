package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.stats.BroadcastingStats;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.OneTimeStreamService;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RadioStationPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationPool.class);
    private final ConcurrentHashMap<String, IStream> pool = new ConcurrentHashMap<>();

    @Inject
    BrandService brandService;

    @Inject
    BroadcasterConfig broadcasterConfig;

    @Inject
    HlsPlaylistConfig hlsPlaylistConfig;

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

    @Inject
    private OneTimeStreamService oneTimeStreamService;

    public Uni<IStream> initializeStation(String brandName) {
        LOGGER.info("Attempting to initialize station for brand: {}", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(bn -> {
                    IStream stationAlreadyActive = pool.get(bn);
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
                                    IStream staleStation = pool.remove(bn);
                                    if (staleStation != null && staleStation.getStreamManager() != null) {
                                        LOGGER.info("Shutting down playlist for stale pooled station {} (not found in DB).", bn);
                                        staleStation.getStreamManager().shutdown();
                                    }
                                    return Uni.createFrom().failure(new RuntimeException("Station not found in DB: " + bn));
                                }

                                IStream finalStationToUse = pool.compute(bn, (key, currentInPool) -> {
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
                                    StreamManager streamManager = new StreamManager(
                                            hlsPlaylistConfig,
                                            broadcasterConfig,
                                            sliderTimer,
                                            feederTimer,
                                            soundFragmentService,
                                            segmentationService,
                                            songSupplier,
                                            updateService,
                                            aiHelperService
                                    );
                                    stationFromDb.setStreamManager(streamManager);
                                    streamManager.setStream(stationFromDb);
                                    streamManager.initialize();

                                    LOGGER.info("RadioStationPool: StreamManager for {} instance created and StreamManager.initialize() called. Status should be WARMING_UP/WAITING.", key);
                                    return stationFromDb;
                                });
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.error("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Uni<IStream> initializeStream(OneTimeStream oneTimeStream) {
        return Uni.createFrom().item(oneTimeStream)
                .onItem().transformToUni(ots -> {
                    IStream stationAlreadyActive = pool.get(ots.getSlugName());
                    if (stationAlreadyActive != null &&
                            (stationAlreadyActive.getStatus() == RadioStationStatus.ON_LINE ||
                                    stationAlreadyActive.getStatus() == RadioStationStatus.WARMING_UP)) {
                        LOGGER.info("Stream {} already active (status: {}). Returning existing instance.", ots.getSlugName(), stationAlreadyActive.getStatus());
                        return Uni.createFrom().item(stationAlreadyActive);
                    }

                    return oneTimeStreamService.getBySlugName(ots.getSlugName())
                            .onItem().transformToUni(stream -> {

                                IStream finalStationToUse = pool.compute(ots.getSlugName(), (key, currentInPool) -> {
                                    if (currentInPool != null &&
                                            (currentInPool.getStatus() == RadioStationStatus.ON_LINE ||
                                                    currentInPool.getStatus() == RadioStationStatus.WARMING_UP)) {
                                        LOGGER.info("Stream {} was concurrently initialized and is active in pool. Using that instance.", key);
                                        return currentInPool;
                                    }

                                    if (currentInPool != null && currentInPool.getStreamManager() != null) {
                                        LOGGER.info("Shutting down stream of existing non-active station {} (status: {}) before replacing.", key, currentInPool.getStatus());
                                        currentInPool.getStreamManager().shutdown();
                                    }

                                    LOGGER.info("RadioStationPool: Creating new StreamManager instance for stream {}.", key);
                                    StreamManager streamManager = new StreamManager(
                                            hlsPlaylistConfig,
                                            broadcasterConfig,
                                            sliderTimer,
                                            feederTimer,
                                            soundFragmentService,
                                            segmentationService,
                                            songSupplier,
                                            updateService,
                                            aiHelperService
                                    );
                                    streamManager.setStream(ots);
                                    streamManager.initialize();
                                    LOGGER.info("RadioStationPool: StreamManager for {} instance created and StreamManager.initialize() called. Status should be WARMING_UP.", key);
                                    return stream;
                                });
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.error("Overall failure to initialize stream {}: {}", oneTimeStream.getSlugName(), failure.getMessage(), failure));
    }

    public Uni<IStream> get(String brandName) {
        IStream stream = pool.get(brandName);
        return Uni.createFrom().item(stream);
    }

    public Uni<IStream> stopAndRemove(String brandName) {
        LOGGER.info("Attempting to stop and remove station: {}", brandName);
        IStream brand = pool.remove(brandName);

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

    public Collection<IStream> getOnlineStationsSnapshot() {
        return new ArrayList<>(pool.values());
    }

    public Set<String> getActiveSlugNamesSnapshot() {
        return new HashSet<>(pool.keySet());
    }

    public Optional<IStream> getStation(String slugName) {
        return Optional.ofNullable(pool.get(slugName));
    }

    public Uni<BroadcastingStats> getLiveStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        IStream brand = pool.get(name);
        if (brand != null) {
            stats.setStatus(brand.getStatus());
        } else {
            stats.setStatus(RadioStationStatus.OFF_LINE);
            stats.setAiControlAllowed(false);
        }
        return Uni.createFrom().item(stats);
    }
}