package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.util.BrandActivityLogger;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandPlaylist.class);
    private String brand;
    private final SoundFragmentRepository repository;
    private final SoundFragmentService soundFragmentService;
    private final RadioStationService radioStationService;
    private final Map<String, PlaylistMemory> brandPlaylistMemory = new ConcurrentHashMap<>();

    public BrandPlaylist(SoundFragmentRepository repository, SoundFragmentService soundFragmentService, RadioStationService radioStationService) {
        this.repository = repository;
        this.soundFragmentService = soundFragmentService;
        this.radioStationService = radioStationService;
    }

    public Uni<List<SoundFragment>> getSongsForBrandPlaylist(String brandName, int quantity) {
        assert repository != null;
        assert radioStationService != null;

        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        BrandActivityLogger.logActivity(brandName, "brand_not_found",
                                "Brand not found");
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    BrandActivityLogger.logActivity(brandName, "fetching_fragments",
                            "Fetching fragments for brand ID: %s", brandId);

                    return repository.getBrandSongs(brandId)
                            .chain(fragments -> {
                                if (fragments.isEmpty()) {
                                    return Uni.createFrom().item(List.<SoundFragment>of());
                                }

                                PlaylistMemory memory = brandPlaylistMemory.computeIfAbsent(brandName, k -> new PlaylistMemory());

                                List<BrandSoundFragment> unplayedFragments = fragments.stream()
                                        .filter(bf -> !memory.wasPlayed(bf.getSoundFragment()))
                                        .collect(Collectors.toList());

                                if (unplayedFragments.isEmpty()) {
                                    memory.reset();
                                    unplayedFragments = fragments;
                                }

                                List<BrandSoundFragment> selectedBrandFragments;
                                if (quantity >= unplayedFragments.size()) {
                                    selectedBrandFragments = unplayedFragments;
                                } else {
                                    selectedBrandFragments = unplayedFragments.subList(0, quantity);
                                }

                                List<SoundFragment> soundFragments = selectedBrandFragments.stream()
                                        .map(BrandSoundFragment::getSoundFragment)
                                        .collect(Collectors.toList());

                                memory.updateLastSelected(soundFragments);
                                return Uni.createFrom().item(soundFragments);
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    return Uni.createFrom().failure(failure);
                });
    }

}
