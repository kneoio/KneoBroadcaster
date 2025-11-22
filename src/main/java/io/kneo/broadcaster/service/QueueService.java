package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.cnst.SSEProgressStatus;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.dto.queue.SSEProgressDTO;
import io.kneo.broadcaster.model.radiostation.RadioStation;
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
        LOGGER.info(" >>>>> request to add to queue from Introcaster {}", toQueueDTO.toString());

        updateProgress(uploadId, SSEProgressStatus.PROCESSING, null);
        if (toQueueDTO.getMergingMethod() == MergingType.INTRO_SONG) {  //keeping JIC
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        try {
                            IntroSongHandler handler = new IntroSongHandler(
                                    broadcasterConfig,
                                    soundFragmentRepository,
                                    soundFragmentService,
                                    aiAgentService,
                                    fFmpegProvider
                            );
                            return handler.handle(radioStation, toQueueDTO);
                        } catch (IOException | AudioMergeException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .onItem().invoke(result -> {
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.NOT_MIXED) {
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        AudioMixingHandler handler = createAudioMixingHandler();
                        return handler.handleConcatenationAndFeed(radioStation, toQueueDTO, ConcatenationType.DIRECT_CONCAT);
                    })
                    .onItem().invoke(result -> {
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.SONG_INTRO_SONG) {
            return getRadioStation(brandName)
                    .chain(radioStation -> createAudioMixingHandler().handleSongIntroSong(radioStation, toQueueDTO))
                    .onItem().invoke(result -> {
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.INTRO_SONG_INTRO_SONG) {
            return getRadioStation(brandName)
                    .chain(radioStation -> createAudioMixingHandler().handleIntroSongIntroSong(radioStation, toQueueDTO))
                    .onItem().invoke(result -> {
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.SONG_CROSSFADE_SONG) {
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        AudioMixingHandler handler = createAudioMixingHandler();
                        ConcatenationType concatType = Arrays.stream(ConcatenationType.values())
                                .skip(new Random().nextInt(ConcatenationType.values().length))
                                .findFirst()
                                .orElse(ConcatenationType.CROSSFADE);
                        return handler.handleConcatenationAndFeed(radioStation, toQueueDTO, concatType);
                    })
                    .onItem().invoke(result -> {
                        updateProgress(uploadId, SSEProgressStatus.DONE, null);
                    })
                    .onFailure().invoke(err -> {
                        updateProgress(uploadId, SSEProgressStatus.ERROR, err.getMessage());
                    });
        } else {
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

    private Uni<RadioStation> getRadioStation(String brand) {
        return radioStationPool.get(brand)
                .onItem().transform(v -> {
                    if (v == null) {
                        throw new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE,
                                String.format("Station not found for brand: %s", brand));
                    }
                    return v;
                });
    }

    public SSEProgressDTO getQueuingProgress(String uploadId) {
        return queuingProgressMap.get(uploadId);
    }

    public void initializeProgress(String uploadId, String name) {
        SSEProgressDTO dto = new SSEProgressDTO();
        dto.setId(uploadId);
        dto.setName(name);
        dto.setStatus(SSEProgressStatus.PROCESSING);
        queuingProgressMap.put(uploadId, dto);
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
