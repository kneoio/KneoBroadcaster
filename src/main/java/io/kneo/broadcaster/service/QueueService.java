package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.cnst.SSEProgressStatus;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.dto.queue.SSEProgressDTO;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.AudioConcatenator;
import io.kneo.broadcaster.service.manipulation.mixing.ConcatenationType;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.manipulation.mixing.handler.AudioMixingHandler;
import io.kneo.broadcaster.service.manipulation.mixing.handler.IntroSongHandler;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class QueueService {
    @Inject
    SoundFragmentRepository soundFragmentRepository;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    private BroadcasterConfig broadcasterConfig;

    @Inject
    private SoundFragmentService soundFragmentService;

    @Inject
    private AiAgentService aiAgentService;

    @Inject
    FFmpegProvider fFmpegProvider;

    @Inject
    AudioConcatenator audioConcatenator;

    private static final Logger LOGGER = LoggerFactory.getLogger(QueueService.class);

    public final ConcurrentHashMap<String, SSEProgressDTO> queuingProgressMap = new ConcurrentHashMap<>();

    public Uni<Boolean> addToQueue(String brandName, AddToQueueDTO toQueueDTO, String uploadId) {
        LOGGER.info("[QueueService] Request to add to queue - uploadId: {}, brandName: {}, mergingMethod: {}, priority: {}", 
                uploadId, brandName, toQueueDTO.getMergingMethod(), toQueueDTO.getPriority());
        LOGGER.debug("[QueueService] AddToQueueDTO details: {}", toQueueDTO.toString());

        updateProgress(uploadId, SSEProgressStatus.PROCESSING, null);
        if (toQueueDTO.getMergingMethod() == MergingType.INTRO_SONG || toQueueDTO.getMergingMethod() == MergingType.LISTENER_INTRO_SONG) {  //keeping JIC
            LOGGER.info("[QueueService] Processing INTRO_SONG merging method for uploadId: {}", uploadId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        LOGGER.info("[QueueService] Radio station found: {} , creating IntroSongHandler",brandName);
                        try {
                            IntroSongHandler handler = new IntroSongHandler(
                                    broadcasterConfig,
                                    soundFragmentRepository,
                                    soundFragmentService,
                                    aiAgentService,
                                    fFmpegProvider
                            );
                            LOGGER.debug("[QueueService] IntroSongHandler created, calling handle method");
                            return handler.handle(radioStation, toQueueDTO);
                        } catch (IOException | AudioMergeException e) {
                            LOGGER.error("[QueueService] Failed to create or execute IntroSongHandler", e);
                            throw new RuntimeException(e);
                        }
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] INTRO_SONG operation completed successfully - uploadId: {}, result: {}", uploadId, result);
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] INTRO_SONG operation failed - uploadId: {}", uploadId, err);
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.NOT_MIXED) {
            LOGGER.info("Processing NOT_MIXED merging method for uploadId: {}", uploadId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        LOGGER.info("Stream found: {}, creating AudioMixingHandler", brandName);
                        AudioMixingHandler handler = createAudioMixingHandler();
                        return handler.handleConcatenationAndFeed(radioStation, toQueueDTO, ConcatenationType.DIRECT_CONCAT);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] NOT_MIXED operation completed successfully - uploadId: {}", uploadId);
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] NOT_MIXED operation failed - uploadId: {}", uploadId, err);
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.SONG_INTRO_SONG) {
            LOGGER.info("[QueueService] Processing SONG_INTRO_SONG merging method for uploadId: {}", uploadId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        LOGGER.info("[QueueService] Radio station found: {}, handling SONG_INTRO_SONG", brandName);
                        return createAudioMixingHandler().handleSongIntroSong(radioStation, toQueueDTO);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] SONG_INTRO_SONG operation completed successfully - uploadId: {}", uploadId);
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] SONG_INTRO_SONG operation failed - uploadId: {}", uploadId, err);
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.INTRO_SONG_INTRO_SONG) {
            LOGGER.info("[QueueService] Processing INTRO_SONG_INTRO_SONG merging method for uploadId: {}", uploadId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        LOGGER.info("[QueueService] Radio station found: {}, handling INTRO_SONG_INTRO_SONG", brandName);
                        return createAudioMixingHandler().handleIntroSongIntroSong(radioStation, toQueueDTO);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] INTRO_SONG_INTRO_SONG operation completed successfully - uploadId: {}", uploadId);
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] INTRO_SONG_INTRO_SONG operation failed - uploadId: {}", uploadId, err);
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.SONG_CROSSFADE_SONG) {
            LOGGER.info("[QueueService] Processing SONG_CROSSFADE_SONG merging method for uploadId: {}", uploadId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        LOGGER.info("[QueueService] Radio station found: {}, creating AudioMixingHandler", brandName);
                        AudioMixingHandler handler = createAudioMixingHandler();
                        ConcatenationType concatType = Arrays.stream(ConcatenationType.values())
                                .skip(new Random().nextInt(ConcatenationType.values().length))
                                .findFirst()
                                .orElse(ConcatenationType.CROSSFADE);
                        LOGGER.debug("[QueueService] Selected concatenation type: {}", concatType);
                        return handler.handleConcatenationAndFeed(radioStation, toQueueDTO, concatType);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] SONG_CROSSFADE_SONG operation completed successfully - uploadId: {}", uploadId);
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] SONG_CROSSFADE_SONG operation failed - uploadId: {}", uploadId, err);
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.SONG_ONLY) {
            LOGGER.info("[QueueService] Processing SONG_ONLY merging method for uploadId: {}", uploadId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        LOGGER.info("[QueueService] Radio station found: {}, handling SONG_ONLY", brandName);
                        return createAudioMixingHandler().handleSongOnly(radioStation, toQueueDTO);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] SONG_ONLY operation completed successfully - uploadId: {}", uploadId);
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] SONG_ONLY operation failed - uploadId: {}", uploadId, err);
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else {
            LOGGER.warn("[QueueService] Unknown or unsupported merging method: {} for uploadId: {}", 
                    toQueueDTO.getMergingMethod(), uploadId);
            return Uni.createFrom().item(Boolean.FALSE);
        }
    }

    private AudioMixingHandler createAudioMixingHandler() {
        try {
            return new AudioMixingHandler(
                    broadcasterConfig,
                    soundFragmentRepository,
                    soundFragmentService,
                    audioConcatenator,
                    aiAgentService,
                    fFmpegProvider
            );
        } catch (IOException | AudioMergeException e) {
            throw new RuntimeException(e);
        }
    }

    private Uni<IStream> getRadioStation(String brand) {
        LOGGER.debug("[QueueService] Looking up radio station for brand: {}", brand);
        return radioStationPool.get(brand)
                .onItem().transform(v -> {
                    if (v == null) {
                        LOGGER.error("[QueueService] Radio station not found for brand: {}", brand);
                        throw new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE,
                                String.format("Station not found for brand: %s", brand));
                    }
                    LOGGER.debug("[QueueService] Radio station retrieved successfully: {}", brand);
                    return v;
                });
    }

    public Uni<Boolean> isStationOnline(String brand) {
        LOGGER.debug("[QueueService] Checking if station is online: {}", brand);
        return radioStationPool.get(brand)
                .onItem().transform(station -> {
                    if (station == null) {
                        return false;
                    }
                    StreamStatus status = station.getStatus();
                    boolean isOnline = status == StreamStatus.ON_LINE ||
                            status == StreamStatus.WARMING_UP ||
                            status == StreamStatus.QUEUE_SATURATED ||
                            status == StreamStatus.IDLE;
                    LOGGER.debug("[QueueService] Station '{}' status: {}, isOnline: {}", brand, status, isOnline);
                    return isOnline;
                });
    }

    private void updateProgress(String uploadId, SSEProgressStatus status, String errorMessage) {
        if (uploadId == null) {
            return;
        }

        SSEProgressDTO dto = queuingProgressMap.get(uploadId);
        if (dto == null) {
            dto = new SSEProgressDTO();
            dto.setId(uploadId);
        }
        dto.setStatus(status);
        dto.setErrorMessage(errorMessage);
        queuingProgressMap.put(uploadId, dto);
    }
}
