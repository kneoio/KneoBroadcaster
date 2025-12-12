package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.StagePlaylist;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragmentFilter;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.util.BrandLogger;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@ApplicationScoped
public class SongSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(SongSupplier.class);
    private static final int CACHE_TTL_MINUTES = 5;

    private final SoundFragmentRepository repository;
    private final BrandService brandService;
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

    public SongSupplier(SoundFragmentRepository repository, BrandService brandService) {
        this.repository = repository;
        this.brandService = brandService;
    }

    private SupplierSongMemory getMemory(String brandName, PlaylistItemType fragmentType) {
        return brandPlaylistMemory
                .computeIfAbsent(brandName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(fragmentType, k -> new ArrayList<>(List.of(new SupplierSongMemory())))
                .get(0);
    }

    public Uni<List<SoundFragment>> getBrandSongs(String brandName, PlaylistItemType fragmentType, int quantity) {
        return getUnplayedFragments(brandName, fragmentType)
                .map(unplayed -> {
                    if (unplayed.isEmpty()) return List.of();

                    List<SoundFragment> shuffled = new ArrayList<>(unplayed);
                    Collections.shuffle(shuffled, ThreadLocalRandom.current());

                    List<SoundFragment> selected = shuffled.stream()
                            .limit(quantity)
                            .collect(Collectors.toList());

                    updateMemory(brandName, fragmentType, selected);
                    return selected;
                });
    }


    public Uni<List<SoundFragment>> getNextSong(String brandName, PlaylistItemType fragmentType, int quantity) {
        return getUnplayedFragments(brandName, fragmentType)
                .map(unplayed -> {
                    if (unplayed.isEmpty()) {
                        return List.<SoundFragment>of();
                    }

                    List<SoundFragment> selected;
                    if (quantity >= unplayed.size()) {
                        selected = unplayed;
                    } else {
                        List<SoundFragment> shuffled = new ArrayList<>(unplayed);
                        //Collections.shuffle(shuffled, new SecureRandom());
                        Collections.shuffle(shuffled, ThreadLocalRandom.current());
                        selected = shuffled.stream()
                                .limit(quantity)
                                .collect(Collectors.toList());
                    }

                    updateMemory(brandName, fragmentType, selected);
                    return selected;
                });
    }

    private Uni<List<SoundFragment>> getUnplayedFragments(String brandName, PlaylistItemType fragmentType) {
        return getBrandDataCached(brandName, fragmentType)
                .map(fragments -> {
                    if (fragments.isEmpty()) {
                        return List.<SoundFragment>of();
                    }

                    SupplierSongMemory memory = getMemory(brandName, fragmentType);
                    List<SoundFragment> unplayedFragments = fragments.stream()
                            .filter(fragment -> !memory.wasPlayed(fragment))
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

        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        BrandLogger.logActivity(brandName, "brand_not_found", "Brand not found");
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    BrandLogger.logActivity(brandName, "fetching_fragments", "Fetching : %s", fragmentType);

                    return repository.getBrandSongsRandomPage(brandId, fragmentType)
                            .flatMap(fragments -> {
                                if (fragments.isEmpty()) {
                                    return repository.getBrandSongs(brandId, fragmentType);
                                }
                                return Uni.createFrom().item(fragments);
                            })
                            .map(fragments -> {
                                Collections.shuffle(fragments);
                                brandCache.put(cacheKey, new CachedBrandData(brandId, fragments));
                                return fragments;
                            });

                });
    }

    private void updateMemory(String brandName, PlaylistItemType fragmentType, List<SoundFragment> selected) {
        getMemory(brandName, fragmentType).updateLastSelected(selected);
    }

    public Uni<List<SoundFragment>> getNextSongByQuery(UUID brandId, StagePlaylist stagePlaylist, int quantity) {
        SoundFragmentFilter filter = buildFilterFromStagePlaylist(stagePlaylist);
        return repository.findByFilter(brandId, filter, quantity)
                .map(fragments -> {
                    if (fragments.isEmpty()) {
                        LOGGER.warn("No fragments found for query filter, brandId: {}", brandId);
                        return List.<SoundFragment>of();
                    }
                    List<SoundFragment> shuffled = new ArrayList<>(fragments);
                    Collections.shuffle(shuffled, ThreadLocalRandom.current());
                    if (quantity >= shuffled.size()) {
                        return shuffled;
                    }
                    return shuffled.stream().limit(quantity).collect(Collectors.toList());
                });
    }

    public Uni<List<SoundFragment>> getNextSongFromStaticList(List<UUID> soundFragmentIds, int quantity) {
        if (soundFragmentIds == null || soundFragmentIds.isEmpty()) {
            LOGGER.warn("Static list is empty or null");
            return Uni.createFrom().item(List.of());
        }
        return repository.findByIds(soundFragmentIds)
                .map(fragments -> {
                    if (fragments.isEmpty()) {
                        LOGGER.warn("No fragments found for static list IDs");
                        return List.<SoundFragment>of();
                    }
                    List<SoundFragment> shuffled = new ArrayList<>(fragments);
                    Collections.shuffle(shuffled, ThreadLocalRandom.current());
                    if (quantity >= shuffled.size()) {
                        return shuffled;
                    }
                    return shuffled.stream().limit(quantity).collect(Collectors.toList());
                });
    }

    private SoundFragmentFilter buildFilterFromStagePlaylist(StagePlaylist stagePlaylist) {
        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setGenre(stagePlaylist.getGenres());
        filter.setLabels(stagePlaylist.getLabels());
        filter.setType(stagePlaylist.getType());
        filter.setSource(stagePlaylist.getSource());
        filter.setSearchTerm(stagePlaylist.getSearchTerm());
        return filter;
    }
}