package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.util.BrandActivityLogger;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class SongSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(SongSupplier.class);
    private static final int CACHE_TTL_MINUTES = 5;

    private final SoundFragmentRepository repository;
    private final RadioStationService radioStationService;
    private final Map<String, Map<PlaylistItemType, List<SupplierSongMemory>>> brandPlaylistMemory = new ConcurrentHashMap<>();
    private final Map<String, CachedBrandData> brandCache = new ConcurrentHashMap<>();

    private static class CachedBrandData {
        final UUID brandId;
        final List<SoundFragment> fragments;
        final LocalDateTime timestamp;

        CachedBrandData(UUID brandId, List<SoundFragment> fragments) {
            this.brandId = brandId;
            this.fragments = fragments;
            this.timestamp = LocalDateTime.now();
        }

        boolean isExpired() {
            return timestamp.plusMinutes(CACHE_TTL_MINUTES).isBefore(LocalDateTime.now());
        }
    }

    public SongSupplier(SoundFragmentRepository repository, RadioStationService radioStationService) {
        this.repository = repository;
        this.radioStationService = radioStationService;
    }

    public Uni<List<SoundFragment>> getBrandSongs(String brandName, PlaylistItemType fragmentType, int quantity) {
        return getUnplayedFragments(brandName, fragmentType)
                .map(unplayed -> {
                    List<SoundFragment> selected;
                    if (quantity >= unplayed.size()) {
                        selected = unplayed;
                    } else {
                        selected = unplayed.subList(0, quantity);
                    }
                    updateMemory(brandName, fragmentType, selected);
                    return selected;
                });
    }

    public Uni<SoundFragment> getNextSong(String brandName, PlaylistItemType fragmentType) {
        return getUnplayedFragments(brandName, fragmentType)
                .map(unplayed -> {
                    if (unplayed.isEmpty()) {
                        return null;
                    }
                    SoundFragment nextSong = unplayed.get((int) (Math.random() * unplayed.size()));
                    updateMemory(brandName, fragmentType, List.of(nextSong));
                    return nextSong;
                });
    }

    private Uni<List<SoundFragment>> getUnplayedFragments(String brandName, PlaylistItemType fragmentType) {
        assert repository != null;
        assert radioStationService != null;

        return getBrandDataCached(brandName, fragmentType)
                .map(fragments -> {
                    if (fragments.isEmpty()) {
                        return List.<SoundFragment>of();
                    }

                    SupplierSongMemory memory = getMemory(brandName, fragmentType);
                    List<SoundFragment> unplayedFragments = fragments.stream()
                            .filter(bf -> !memory.wasPlayed(bf))
                            .collect(Collectors.toList());

                    if (unplayedFragments.isEmpty()) {
                        memory.reset();
                        unplayedFragments = fragments;
                    }

                    return unplayedFragments;
                });
    }

    private Uni<List<SoundFragment>> getBrandDataCached(String brandName, PlaylistItemType fragmentType) {
        String cacheKey = brandName + "_" + fragmentType;
        CachedBrandData cached = brandCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {
            return Uni.createFrom().item(cached.fragments);
        }

        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        BrandActivityLogger.logActivity(brandName, "brand_not_found", "Brand not found");
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    BrandActivityLogger.logActivity(brandName, "fetching_fragments", "Fetching fragments for brand ID: %s", brandId);

                    return repository.getBrandSongs(brandId, fragmentType)
                            .map(fragments -> {
                                brandCache.put(cacheKey, new CachedBrandData(brandId, fragments));
                                return fragments;
                            });
                });
    }

    private SupplierSongMemory getMemory(String brandName, PlaylistItemType fragmentType) {
        return brandPlaylistMemory
                .computeIfAbsent(brandName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(fragmentType, k -> List.of(new SupplierSongMemory()))
                .get(0);
    }

    private void updateMemory(String brandName, PlaylistItemType fragmentType, List<SoundFragment> selected) {
        getMemory(brandName, fragmentType).updateLastSelected(selected);
    }
}