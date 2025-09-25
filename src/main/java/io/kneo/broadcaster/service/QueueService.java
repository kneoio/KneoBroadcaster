package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.mcp.AddToQueueMcpDTO;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
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

@ApplicationScoped
public class QueueService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueService.class);

    @Inject
    SoundFragmentRepository repository;

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

    public Uni<Boolean> addToQueue(String brandName, AddToQueueMcpDTO toQueueDTO) {
        if (toQueueDTO.getMergingMethod() == MergingType.INTRO_SONG || toQueueDTO.getMergingMethod() == MergingType.FILLER_SONG) {
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        try {
                            IntroSongHandler handler = new IntroSongHandler(
                                    broadcasterConfig,
                                    repository,
                                    soundFragmentService,
                                    aiAgentService,
                                    fFmpegProvider
                            );
                            return handler.handle(radioStation, toQueueDTO);
                        } catch (IOException | AudioMergeException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.SONG_INTRO_SONG) {
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        AudioMixingHandler songIntroSongHandler = null;
                        try {
                            songIntroSongHandler = new AudioMixingHandler(
                                    broadcasterConfig,
                                    repository,
                                    soundFragmentService,
                                    aiAgentService,
                                    fFmpegProvider
                            );
                        } catch (IOException | AudioMergeException e) {
                            throw new RuntimeException(e);
                        }
                        return songIntroSongHandler.handle(radioStation, toQueueDTO);
                    });
        } else if (toQueueDTO.getMergingMethod() == MergingType.INTRO_SONG_INTRO_SONG) {
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        AudioMixingHandler songIntroSongHandler = null;
                        try {
                            songIntroSongHandler = new AudioMixingHandler(
                                    broadcasterConfig,
                                    repository,
                                    soundFragmentService,
                                    aiAgentService,
                                    fFmpegProvider
                            );
                        } catch (IOException | AudioMergeException e) {
                            throw new RuntimeException(e);
                        }
                        return songIntroSongHandler.handle(radioStation, toQueueDTO);
                    });
        } else {
            return Uni.createFrom().item(Boolean.FALSE);
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
}