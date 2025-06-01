package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.SoundFragmentRepository;
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
import java.util.List;
import java.util.PriorityQueue;
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
                .onItem().transformToUni(radioStation -> {
                    if (radioStation != null && radioStation.getPlaylist() != null && radioStation.getPlaylist().getPlaylistManager() != null) {
                        PriorityQueue<BrandSoundFragment> fragments = radioStation.getPlaylist().getPlaylistManager().getSegmentedAndReadyToBeConsumed();
                        if (fragments.isEmpty()) {
                            return Uni.createFrom().item(List.<SoundFragmentDTO>of());
                        }
                        List<Uni<SoundFragmentDTO>> unis = fragments.stream()
                                .map(this::mapToBrandSoundFragmentDTO)
                                .collect(Collectors.toList());

                        return Uni.join().all(unis).andCollectFailures();
                    } else {
                        LOGGER.warn("RadioStation or Playlist not found for brand: {}", brandName);
                        return Uni.createFrom().item(List.<SoundFragmentDTO>of());
                    }
                })
                .onFailure().invoke(failure ->
                        LOGGER.error("Error getting queue for brand {}: {}", brandName, failure.getMessage(), failure)
                );
    }

    public Uni<Boolean> addToQueue(String brandName, UUID soundFragmentId, String filePath) {
        return repository.findById(soundFragmentId, SuperUser.ID, false)
                .chain(soundFragment -> {
                    if (filePath != null && !filePath.isEmpty()) {
                        try {
                            Path mergedPath = audioMergerService.mergeAudioFiles(
                                    Path.of(filePath),
                                    soundFragment.getFilePath(), 0
                            );
                            soundFragment.setTitle(String.format(" ## %s", soundFragment.getTitle()));
                            soundFragment.setFilePath(mergedPath);
                        } catch (Exception e) {
                            LOGGER.error("Failed to merge audio files: {}", e.getMessage(), e);
                            return Uni.createFrom().failure(e);
                        }
                    } else {
                        repository.findById(soundFragmentId, SuperUser.ID, false);
                    }

                    return getPlaylist(brandName)
                            .onItem().transformToUni(radioStation -> {
                                if (radioStation != null) {
                                    radioStation.setManagedBy(ManagedBy.AI_AGENT);
                                    BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
                                    brandSoundFragment.setId(soundFragment.getId());
                                    brandSoundFragment.setSoundFragment(soundFragment);
                                    brandSoundFragment.setQueueNum(10);
                                    boolean result = radioStation.getPlaylist().getPlaylistManager().addFragmentToSlice(brandSoundFragment);
                                    LOGGER.info("Added merged song to queue for brand {}: {}", brandName, soundFragment.getTitle());
                                    return Uni.createFrom().item(result);
                                } else {
                                    LOGGER.warn("RadioStation or Playlist not found for brand: {}", brandName);
                                    return Uni.createFrom().item(false);
                                }
                            });
                })
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.error("Error adding to queue for brand {}: {}", brandName, failure.getMessage(), failure);
                    return false;
                });
    }

    private Uni<RadioStation> getPlaylist(String brand) {
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