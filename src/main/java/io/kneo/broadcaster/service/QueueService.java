package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.mcp.AddToQueueMcpDTO;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.AudioMergerService;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.manipulation.mixing.handler.AudioMixingHandler;
import io.kneo.broadcaster.service.manipulation.mixing.handler.IntroSongHandler;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.exception.attachment.FileRetrievalFailureException;
import io.kneo.core.repository.exception.attachment.MissingFileRecordException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class QueueService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueService.class);

    @Inject
    SoundFragmentRepository repository;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    AudioMergerService audioMergerService;

    @Inject
    private BroadcasterConfig broadcasterConfig;

    @Inject
    private SoundFragmentService soundFragmentService;

    @Inject
    FFmpegProvider fFmpegProvider;

    public Uni<Boolean> addToQueue(String brandName, AddToQueueMcpDTO toQueueDTO) {
            if (toQueueDTO.getMergingMethod() == MergingType.INTRO_SONG || toQueueDTO.getMergingMethod() == MergingType.FILLER_SONG) {
                return getRadioStation(brandName)
                        .chain(radioStation -> {
                            IntroSongHandler handler = new IntroSongHandler(
                                    broadcasterConfig,
                                    repository,
                                    audioMergerService,
                                    soundFragmentService,
                                    fFmpegProvider
                            );
                            try {
                                return handler.handle(radioStation, toQueueDTO);
                            } catch (IOException e) {
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


    @Deprecated
    public Uni<Boolean> addToQueue(String brandName, UUID soundFragmentId, List<String> ttsFilePaths) {
        return getRadioStation(brandName)
                .chain(radioStation -> {
                    PlaylistManager playlistManager = radioStation.getStreamManager().getPlaylistManager();

                    return soundFragmentService.getById(soundFragmentId, SuperUser.build())
                            .chain(soundFragment -> {
                                return repository.getFirstFile(soundFragment.getId())
                                        .onFailure(MissingFileRecordException.class)
                                        .recoverWithUni(ex -> {
                                            return repository.markAsCorrupted(soundFragment.getId())
                                                    .chain(() -> Uni.createFrom().failure(ex));
                                        })
                                        .onFailure(FileRetrievalFailureException.class)
                                        .recoverWithUni(ex -> {
                                            return repository.markAsCorrupted(soundFragment.getId())
                                                    .chain(() -> Uni.createFrom().failure(ex));
                                        })
                                        .chain(songMetadata -> {
                                            // Check prioritizedQueue size and remove last if > 2
                                            if (playlistManager.getPrioritizedQueue().size() > 2) {
                                                LiveSoundFragment lastFragment = playlistManager.getPrioritizedQueue().getLast();
                                                LOGGER.info("prioritizedQueue: {}", lastFragment.getMetadata());
                                            }

                                            if (ttsFilePaths != null && !ttsFilePaths.isEmpty()) {
                                                String fileName = Path.of(ttsFilePaths.get(0)).getFileName().toString();
                                                String dashPrefix;
                                                if (fileName.contains("_--")) {
                                                    dashPrefix = " -- ";
                                                } else {
                                                    dashPrefix = " - ";
                                                }

                                                return audioMergerService.mergeAudioFiles(Path.of(ttsFilePaths.get(0)), songMetadata, radioStation)
                                                        .onItem().transform(mergedPath -> {
                                                            songMetadata.setTemporaryFilePath(mergedPath);
                                                            return songMetadata;
                                                        })
                                                        .onItem().transform(finalMetadata -> {
                                                            soundFragment.setFileMetadataList(List.of(finalMetadata));
                                                            soundFragment.setTitle(dashPrefix + soundFragment.getTitle());
                                                            return finalMetadata;
                                                        }).chain(updatedMetadata -> {
                                                            radioStation.setAiAgentStatus(AiAgentStatus.CONTROLLING);
                                                            radioStation.setLastAgentContactAt(System.currentTimeMillis());
                                                            BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                                            brandSoundFragment.setId(soundFragment.getId());
                                                            brandSoundFragment.setSoundFragment(soundFragment);
                                                            brandSoundFragment.setQueueNum(10);

                                                            return playlistManager.addFragmentToSlice(brandSoundFragment, radioStation.getBitRate())
                                                                    .onItem().invoke(result -> {
                                                                        if (result) {
                                                                            LOGGER.info("Added merged song to queue for brand {}: {}", brandName, soundFragment.getTitle());
                                                                        }
                                                                    });
                                                        });
                                            } else {
                                                radioStation.setAiAgentStatus(AiAgentStatus.CONTROLLING);
                                                radioStation.setLastAgentContactAt(System.currentTimeMillis());
                                                BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                                brandSoundFragment.setId(soundFragment.getId());
                                                brandSoundFragment.setSoundFragment(soundFragment);
                                                brandSoundFragment.setQueueNum(10);

                                                return playlistManager.addFragmentToSlice(brandSoundFragment, radioStation.getBitRate())
                                                        .onItem().invoke(result -> {
                                                            if (result) {
                                                                LOGGER.info("Added song to queue for brand {}: {}", brandName, soundFragment.getTitle());
                                                            }
                                                        });
                                            }
                                        });
                            });
                })
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.error("Error adding to queue for brand {}: {}", brandName, failure.getMessage(), failure);
                    radioStationPool.get(brandName)
                            .subscribe().with(
                                    station -> {
                                        if (station != null) {
                                            station.setStatus(RadioStationStatus.SYSTEM_ERROR);
                                            LOGGER.warn("Station {} status set to SYSTEM_ERROR due to addToQueue failure", brandName);
                                        }
                                    },
                                    error -> LOGGER.error("Failed to get station {} to set error status: {}", brandName, error.getMessage(), error)
                            );
                    return false;
                });
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