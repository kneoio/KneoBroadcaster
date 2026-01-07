package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.stats.BroadcastingStats;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.RadioStream;
import io.kneo.broadcaster.repository.OneTimeStreamRepository;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.OneTimeStreamService;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.service.manipulation.segmentation.AudioSegmentationService;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.BrandSoundFragmentUpdateService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.util.AiHelperUtils;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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
    private StreamScheduleService streamScheduleService;

    @Inject
    private OneTimeStreamService oneTimeStreamService;

    @Inject
    private OneTimeStreamRepository oneTimeStreamRepository;

    @Inject
    private AiAgentService aiAgentService;

    public Uni<IStream> initializeRadio(String brandName) {
        LOGGER.info("Attempting to initialize Radio Stream for brand: {}", brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(bn -> {
                    IStream stationAlreadyActive = pool.get(bn);
                    if (stationAlreadyActive != null &&
                            (stationAlreadyActive.getStatus() == StreamStatus.ON_LINE ||
                                    stationAlreadyActive.getStatus() == StreamStatus.WARMING_UP)) {
                        LOGGER.info("Radio Stream {} already active (status: {}) or warming up. Returning existing instance from initial check.", bn, stationAlreadyActive.getStatus());
                        return Uni.createFrom().item(stationAlreadyActive);
                    }

                    return brandService.getBySlugName(bn)
                            .onItem().transformToUni(brand -> {
                                if (brand == null) {
                                    LOGGER.warn("Brand with brandName {} not found in database. Cannot initialize.", bn);
                                    IStream staleStation = pool.remove(bn);
                                    return Uni.createFrom().failure(new RuntimeException("Station not found in DB: " + bn));
                                }

                                IStream finalStationToUse = pool.compute(bn, (key, currentInPool) -> {
                                    if (currentInPool != null &&
                                            (currentInPool.getStatus() == StreamStatus.ON_LINE ||
                                                    currentInPool.getStatus() == StreamStatus.WARMING_UP)) {
                                        LOGGER.info("Radio stream {} was concurrently initialized and is active in pool. Using that instance.", key);
                                        return currentInPool;
                                    }

                                    LOGGER.info("RadioStationPool: Creating new StreamManager instance for brand {}.", key);
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
                                    RadioStream radioStream = new RadioStream(brand);
                                    streamManager.initialize(radioStream);
                                    radioStream.setStreamManager(streamManager);

                                    LOGGER.info("RadioStationPool: StreamManager for {} instance created and StreamManager.initialize() called. Status should be WARMING_UP", key);
                                    return radioStream;
                                });

                                if (finalStationToUse instanceof RadioStream radioStream && radioStream.getStreamSchedule() == null) {
                                    LOGGER.info("RadioStationPool: Building looped schedule for RadioStream '{}'", radioStream.getSlugName());
                                    return streamScheduleService.buildLoopedStreamSchedule(brand.getId(), brand.getScripts().getFirst().getScriptId(), SuperUser.build())
                                            .invoke(schedule -> {
                                                radioStream.setStreamSchedule(schedule);
                                                LOGGER.info("RadioStationPool: Schedule set for '{}': {} scenes, {} songs",
                                                        radioStream.getSlugName(),
                                                        schedule != null ? schedule.getTotalScenes() : 0,
                                                        schedule != null ? schedule.getTotalSongs() : 0);
                                            })
                                            .map(schedule -> (IStream) radioStream);
                                }
                                return Uni.createFrom().item(finalStationToUse);
                            });
                })
                .onFailure().invoke(failure -> LOGGER.error("Overall failure to initialize station {}: {}", brandName, failure.getMessage(), failure));
    }

    public Uni<IStream> initializeStream(IStream oneTimeStream) {
        return Uni.createFrom().item(oneTimeStream)
                .onItem().transformToUni(ots -> {
                    IStream stationAlreadyActive = pool.get(ots.getSlugName());
                    if (stationAlreadyActive != null &&
                            (stationAlreadyActive.getStatus() == StreamStatus.ON_LINE ||
                                    stationAlreadyActive.getStatus() == StreamStatus.WARMING_UP)) {
                        LOGGER.info("Stream {} already active (status: {}). Returning existing instance.", ots.getSlugName(), stationAlreadyActive.getStatus());
                        return Uni.createFrom().item(stationAlreadyActive);
                    }

                    return oneTimeStreamService.getBySlugName(ots.getSlugName())
                            .onItem().transformToUni(stream -> {
                                
                                if (stream.getAiAgentId() != null) {
                                    return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .onItem().transform(agent -> {
                                                LanguageTag selectedLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                                                stream.setStreamLanguage(selectedLanguage);
                                                LOGGER.info("Set stream language to '{}' for stream '{}' based on AI agent '{}'", 
                                                    selectedLanguage.tag(), stream.getSlugName(), agent.getName());
                                                return stream;
                                            })
                                            .onFailure().invoke(failure -> {
                                                LOGGER.warn("Failed to resolve AI agent for stream '{}', using default language: {}", 
                                                    stream.getSlugName(), failure.getMessage());
                                                stream.setStreamLanguage(LanguageTag.EN_US);
                                            })
                                            .onFailure().recoverWithItem(() -> {
                                                stream.setStreamLanguage(LanguageTag.EN_US);
                                                return stream;
                                            });
                                } else {
                                    LOGGER.warn("No AI Agent ID set for stream '{}', using default language", stream.getSlugName());
                                    stream.setStreamLanguage(LanguageTag.EN_US);
                                    return Uni.createFrom().item(stream);
                                }
                            })
                            .onItem().transformToUni(stream -> {

                                IStream finalStationToUse = pool.compute(ots.getSlugName(), (key, currentInPool) -> {
                                    if (currentInPool != null &&
                                            (currentInPool.getStatus() == StreamStatus.ON_LINE ||
                                                    currentInPool.getStatus() == StreamStatus.WARMING_UP)) {
                                        LOGGER.info("Stream {} was concurrently initialized and is active in pool. Using that instance.", key);
                                        return currentInPool;
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
                                    streamManager.initialize(stream);
                                    stream.setStreamManager(streamManager);
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
            LOGGER.info("Station {} found in pool and removed. Shutting down StreamManager.", brandName);
            
            if (brand.getStreamManager() != null) {
                brand.getStreamManager().shutdown();
            }
            
            brand.setStatus(StreamStatus.OFF_LINE);
            
            if (brand instanceof OneTimeStream oneTimeStream) {
                return oneTimeStreamRepository.getBySlugName(brandName)
                        .onItem().invoke(repoStream -> {
                            if (repoStream != null) {
                                // Preserve the current status if it's already FINISHED, otherwise set to OFF_LINE
                                StreamStatus newStatus = oneTimeStream.getStatus() == StreamStatus.FINISHED 
                                    ? StreamStatus.FINISHED 
                                    : StreamStatus.OFF_LINE;
                                repoStream.setStatus(newStatus);
                                LOGGER.info("Updated repository status to {} for OneTimeStream station: {}", newStatus, brandName);
                            }
                        })
                        .replaceWith(brand);
            }
            
            return Uni.createFrom().item(brand);
        } else {
            LOGGER.warn("Station {} not found in pool during stopAndRemove.", brandName);
            return Uni.createFrom().nullItem();
        }
    }

    public Collection<IStream> getOnlineStationsSnapshot() {
        return new ArrayList<>(pool.values());
    }

    public Set<String> getActiveSnapshot() {
        return new HashSet<>(pool.keySet());
    }

    public IStream getStation(String slugName) {
        return pool.get(slugName);
    }

    public Uni<BroadcastingStats> getLiveStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        IStream brand = pool.get(name);
        if (brand != null) {
            stats.setStatus(brand.getStatus());
        } else {
            stats.setStatus(StreamStatus.OFF_LINE);
            stats.setAiControlAllowed(false);
        }
        return Uni.createFrom().item(stats);
    }

}
