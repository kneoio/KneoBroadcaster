package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.dto.queue.IntroPlusSong;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.manipulation.AudioMergerService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
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
    private SoundFragmentService soundFragmentService;

    public Uni<List<SoundFragmentDTO>> getQueueForBrand(String brandName) {
        return getRadioStation(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation != null && radioStation.getPlaylist() != null && radioStation.getPlaylist().getPlaylistManager() != null) {
                        var playlistManager = radioStation.getPlaylist().getPlaylistManager();

                        List<Uni<SoundFragmentDTO>> unis = new ArrayList<>();
                        playlistManager.getPrioritizedQueue().stream()
                                .map(this::mapToBrandSoundFragmentDTO)
                                .forEach(unis::add);
                        playlistManager.getRegularQueue().stream()
                                .map(this::mapToBrandSoundFragmentDTO)
                                .forEach(unis::add);

                        if (unis.isEmpty()) {
                            return Uni.createFrom().item(List.<SoundFragmentDTO>of());
                        }
                        return Uni.join().all(unis).andCollectFailures();
                    } else {
                        LOGGER.warn("RadioStation or Playlist not found for brand: {}", brandName);
                        return Uni.createFrom().item(List.<SoundFragmentDTO>of());
                    }
                });
    }

    //TODO for MCP call it is not used yet
    public Uni<Boolean> addToQueue(String brandName, AddToQueueDTO toQueueDTO) {
        if (toQueueDTO.getMergingMethod() instanceof IntroPlusSong method) {
            UUID soundFragmentId = method.getSoundFragmentUUID();
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        if (radioStation == null) {
                            return Uni.createFrom().item(Boolean.FALSE);
                        }

                        return soundFragmentService.getById(soundFragmentId, SuperUser.build())
                                .chain(soundFragment -> {
                                    return repository.getFirstFile(soundFragment.getId())
                                            .chain(metadata -> {
                                                return audioMergerService.mergeAudioFiles(
                                                                method.getFilePath(),
                                                                metadata, radioStation)
                                                        .onItem().transform(mergedPath -> {
                                                            metadata.setTemporaryFilePath(mergedPath);
                                                            soundFragment.setFileMetadataList(List.of(metadata));
                                                            soundFragment.setTitle(soundFragment.getTitle());
                                                            return metadata;
                                                        })
                                                        .chain(updatedMetadata -> {
                                                            radioStation.setAiAgentStatus(AiAgentStatus.CONTROLLING);
                                                            BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                                            brandSoundFragment.setId(soundFragment.getId());
                                                            brandSoundFragment.setSoundFragment(soundFragment);
                                                            brandSoundFragment.setQueueNum(toQueueDTO.getPriority());

                                                            return radioStation.getPlaylist().getPlaylistManager()
                                                                    .addFragmentToSlice(brandSoundFragment)
                                                                    .onItem().invoke(result -> {
                                                                        if (result) {
                                                                            LOGGER.info("Added song to queue for brand {}: {}",
                                                                                    brandName, soundFragment.getTitle());
                                                                        }
                                                                    })
                                                                    .onItem().transform(Boolean::valueOf);
                                                        });
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
                        return Boolean.FALSE;
                    });
        } else {
            return Uni.createFrom().item(Boolean.FALSE);
        }
    }

    @Deprecated
    public Uni<Boolean> addToQueue(String brandName, UUID soundFragmentId, List<String> ttsFilePaths) {
        return getRadioStation(brandName)
                .chain(radioStation -> {
                    if (radioStation == null) {
                        LOGGER.warn("RadioStation {} not found", brandName);
                        return Uni.createFrom().item(false);
                    }

                    return soundFragmentService.getById(soundFragmentId, SuperUser.build())
                            .chain(soundFragment -> repository.getFirstFile(soundFragment.getId())
                                    .chain(songMetadata -> {
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
                                                        //override original file
                                                        songMetadata.setTemporaryFilePath(mergedPath);
                                                        return songMetadata;
                                                    })
                                                    .onItem().transform(finalMetadata -> {
                                                        soundFragment.setFileMetadataList(List.of(finalMetadata));
                                                        soundFragment.setTitle(dashPrefix + soundFragment.getTitle());
                                                        return finalMetadata;
                                                    }).chain(updatedMetadata -> {
                                                        radioStation.setAiAgentStatus(AiAgentStatus.CONTROLLING);
                                                        BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                                        brandSoundFragment.setId(soundFragment.getId());
                                                        brandSoundFragment.setSoundFragment(soundFragment);
                                                        brandSoundFragment.setQueueNum(10);

                                                        return radioStation.getPlaylist().getPlaylistManager()
                                                                .addFragmentToSlice(brandSoundFragment)
                                                                .onItem().invoke(result -> {
                                                                    if (result) {
                                                                        LOGGER.info("Added merged song to queue for brand {}: {}",
                                                                                brandName, soundFragment.getTitle());
                                                                    }
                                                                });
                                                    });
                                        } else {
                                            radioStation.setAiAgentStatus(AiAgentStatus.CONTROLLING);
                                            BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                            brandSoundFragment.setId(soundFragment.getId());
                                            brandSoundFragment.setSoundFragment(soundFragment);
                                            brandSoundFragment.setQueueNum(10);

                                            return radioStation.getPlaylist().getPlaylistManager()
                                                    .addFragmentToSlice(brandSoundFragment)
                                                    .onItem().invoke(result -> {
                                                        if (result) {
                                                            LOGGER.info("Added song to queue for brand {}: {}",
                                                                    brandName, soundFragment.getTitle());
                                                        }
                                                    });
                                        }
                                    }));
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
                        LOGGER.warn("Station not found for brand: {}", brand);
                        throw new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE,
                                String.format("Station not found for brand: %s", brand));
                    }
                    return v;
                })
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to get station for brand: {}", brand, failure)
                );
    }

    private Uni<SoundFragmentDTO> mapToBrandSoundFragmentDTO(BrandSoundFragment fragment) {
        return Uni.createFrom().item(() -> {
            SoundFragment soundFragment = fragment.getSoundFragment();

            SoundFragmentDTO soundFragmentDTO = new SoundFragmentDTO(soundFragment.getId().toString());
            soundFragmentDTO.setTitle(soundFragment.getTitle());
            soundFragmentDTO.setArtist(soundFragment.getArtist());
            soundFragmentDTO.setAlbum(soundFragment.getAlbum());
            soundFragmentDTO.setSource(SourceType.USERS_UPLOAD);

            return soundFragmentDTO;
        });
    }
}