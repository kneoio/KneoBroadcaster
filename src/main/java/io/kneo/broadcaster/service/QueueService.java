package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.SoundFragmentRepository;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.manipulation.AudioMergerService;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class QueueService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueService.class);

    @Inject
    SoundFragmentRepository repository;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    AudioMergerService audioMergerService;

    public Uni<List<SoundFragmentDTO>> getQueueForBrand(String brandName) {
        return getPlaylist(brandName)
                .onItem().transformToUni(playlist -> {
                    if (playlist != null && playlist.getPlaylistManager() != null) {
                        LinkedList<BrandSoundFragment> fragments = playlist.getPlaylistManager().getReadyFragmentsToSlice();
                        if (fragments.isEmpty()) {
                            return Uni.createFrom().item(List.<SoundFragmentDTO>of());
                        }
                        List<Uni<SoundFragmentDTO>> unis = fragments.stream()
                                .map(this::mapToBrandSoundFragmentDTO)
                                .collect(Collectors.toList());

                        return Uni.join().all(unis).andCollectFailures();
                    } else {
                        LOGGER.warn("Playlist or PlaylistManager not found for brand: {}", brandName);
                        return Uni.createFrom().item(List.<SoundFragmentDTO>of());
                    }
                })
                .onFailure().invoke(failure ->
                        LOGGER.error("Error getting queue for brand {}: {}", brandName, failure.getMessage(), failure)
                );
    }

    public Uni<String> getCurrentlyPlayingSong(String brandName) {
        return getPlaylist(brandName)
                .onItem().transform(playlist -> {
                    if (playlist != null && playlist.getPlaylistManager() != null) {
                        String currentlyPlaying = playlist.getPlaylistManager().getCurrentlyPlaying();
                        LOGGER.debug("Current song for brand {}: {}", brandName, currentlyPlaying);
                        return currentlyPlaying;
                    } else {
                        LOGGER.warn("Playlist or PlaylistManager not found for brand: {}", brandName);
                        return null;
                    }
                })
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.error("Error getting current song for brand {}: {}", brandName, failure.getMessage());
                    return null;
                });
    }

    public Uni<Boolean> addToQueue(String brandName, UUID soundFragmentId) {
        return repository.findById(soundFragmentId, SuperUser.ID)
                .chain(soundFragment -> getPlaylist(brandName)
                        .onItem().transformToUni(playlist -> {
                            if (playlist != null && playlist.getPlaylistManager() != null) {
                                BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                brandSoundFragment.setSoundFragment(soundFragment);
                                boolean result = playlist.getPlaylistManager().addToQueue(brandSoundFragment);
                                LOGGER.info("Added song to queue for brand {}: {}", brandName, soundFragment.getTitle());
                                return Uni.createFrom().item(result);
                            } else {
                                LOGGER.warn("The fragment not found and not been added for brand: {}", brandName);
                                return Uni.createFrom().item(false);
                            }
                        })
                )
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.error("Error adding to queue for brand {}: {}", brandName, failure.getMessage(), failure);
                    return false;
                });
    }



    public Uni<Boolean> addToQueue(String brandName, UUID soundFragmentId, String filePath) {
        return repository.findById(soundFragmentId, SuperUser.ID)
                .chain(soundFragment -> {
                    if (filePath != null && !filePath.isEmpty()) {
                        try {
                            Path mergedPath = audioMergerService.mergeAudioFiles(
                                    soundFragment.getFilePath(),
                                    Path.of(filePath), 0
                            );
                            soundFragment.setFilePath(mergedPath);
                        } catch (Exception e) {
                            LOGGER.error("Failed to merge audio files: {}", e.getMessage(), e);
                            return Uni.createFrom().failure(e);
                        }
                    }

                    return getPlaylist(brandName)
                            .onItem().transformToUni(playlist -> {
                                if (playlist != null && playlist.getPlaylistManager() != null) {
                                    BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                    brandSoundFragment.setSoundFragment(soundFragment);
                                    boolean result = playlist.getPlaylistManager().addToQueue(brandSoundFragment);
                                    LOGGER.info("Added merged song to queue for brand {}: {}", brandName, soundFragment.getTitle());
                                    return Uni.createFrom().item(result);
                                } else {
                                    LOGGER.warn("Playlist not found for brand: {}", brandName);
                                    return Uni.createFrom().item(false);
                                }
                            });
                })
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.error("Error adding to queue for brand {}: {}", brandName, failure.getMessage(), failure);
                    return false;
                });
    }

    private Uni<HLSPlaylist> getPlaylist(String brand) {
        return radioStationPool.get(brand)
                .onItem().transform(v -> {
                    if (v == null || v.getPlaylist() == null || v.getPlaylist().getSegmentCount() == 0) {
                        LOGGER.warn("Station not initialized for brand: {}", brand);
                        throw new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE,
                                String.format("Station not initialized for brand: %s", brand));
                    }
                    return v.getPlaylist();
                })
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to get playlist for brand: {}", brand, failure)
                );
    }

    private Uni<SoundFragmentDTO> mapToBrandSoundFragmentDTO(BrandSoundFragment fragment) {
        return Uni.createFrom().item(() -> {
            SoundFragment soundFragment = fragment.getSoundFragment();

            SoundFragmentDTO soundFragmentDTO = new SoundFragmentDTO(soundFragment.getId().toString());
            soundFragmentDTO.setTitle(soundFragment.getTitle());
            soundFragmentDTO.setArtist(soundFragment.getArtist());
            soundFragmentDTO.setAlbum(soundFragment.getAlbum());
            soundFragmentDTO.setSource(SourceType.LOCAL);

            return soundFragmentDTO;
        });
    }
}