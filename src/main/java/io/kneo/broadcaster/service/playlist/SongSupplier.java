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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
        return getWeightedRandomFragments(brandName, fragmentType, quantity);
    }

    public Uni<List<SoundFragment>> getNextSong(String brandName, PlaylistItemType fragmentType, int quantity) {
        return getWeightedRandomFragments(brandName, fragmentType, quantity);
    }

    private Uni<List<SoundFragment>> getWeightedRandomFragments(String brandName, PlaylistItemType fragmentType, int quantity) {
        return getBrandDataCached(brandName, fragmentType)
                .map(fragments -> {
                    if (fragments.isEmpty()) {
                        return List.<SoundFragment>of();
                    }

                    SupplierSongMemory memory = getMemory(brandName, fragmentType);

                    // Calculate weights based on play frequency
                    Map<SoundFragment, Integer> playCountMap = memory.getPlayCounts(fragments);
                    int maxPlayCount = playCountMap.values().stream().max(Integer::compareTo).orElse(0);

                    // Create weighted selection pool
                    List<WeightedSong> weightedSongs = fragments.stream()
                            .map(fragment -> {
                                int playCount = playCountMap.getOrDefault(fragment, 0);
                                // Higher weight for less played songs (inverse weight)
                                int weight = Math.max(1, (maxPlayCount + 1) - playCount);
                                return new WeightedSong(fragment, weight);
                            })
                            .collect(Collectors.toList());

                    List<SoundFragment> selected = selectWeightedRandom(weightedSongs, quantity);
                    updateMemory(brandName, fragmentType, selected);
                    return selected;
                });
    }

    private List<SoundFragment> selectWeightedRandom(List<WeightedSong> weightedSongs, int quantity) {
        if (weightedSongs.isEmpty()) {
            return List.of();
        }

        // Calculate total weight
        int totalWeight = weightedSongs.stream().mapToInt(ws -> ws.weight).sum();

        List<SoundFragment> selected = new ArrayList<>();
        List<WeightedSong> available = new ArrayList<>(weightedSongs);

        for (int i = 0; i < quantity && !available.isEmpty(); i++) {
            int randomValue = ThreadLocalRandom.current().nextInt(
                    available.stream().mapToInt(ws -> ws.weight).sum()
            );

            int currentWeight = 0;
            WeightedSong selectedSong = null;

            for (WeightedSong weightedSong : available) {
                currentWeight += weightedSong.weight;
                if (randomValue < currentWeight) {
                    selectedSong = weightedSong;
                    break;
                }
            }

            if (selectedSong != null) {
                selected.add(selectedSong.fragment);
                available.remove(selectedSong); // Prevent duplicates in same selection
            }
        }

        return selected;
    }

    private static class WeightedSong {
        final SoundFragment fragment;
        final int weight;

        WeightedSong(SoundFragment fragment, int weight) {
            this.fragment = fragment;
            this.weight = weight;
        }
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