package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.PlaylistRequest;
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

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class SongSupplier implements ISupplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(SongSupplier.class);
    private static final int CACHE_TTL_MINUTES = 5;

    private final SoundFragmentRepository repository;
    private final BrandService brandService;

    private final Map<String, CachedBrandData> brandCache = new ConcurrentHashMap<>();

    private final SecureRandom secureRandom = new SecureRandom();

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

    @Override
    public Uni<List<SoundFragment>> getBrandSongs(String brandName, UUID brandId, PlaylistItemType fragmentType, int quantity) {
        return getBrandDataCached(brandName, brandId, fragmentType)
                .map(fragments -> {
                    if (fragments.isEmpty()) return List.of();

                    List<SoundFragment> shuffled = new ArrayList<>(fragments);
                    Collections.shuffle(shuffled, secureRandom);

                    return shuffled.stream()
                            .limit(quantity)
                            .collect(Collectors.toList());
                });
    }

    public Uni<List<SoundFragment>> getNextSong(String brandName, PlaylistItemType fragmentType, int quantity) {
        return getBrandDataCached(brandName, null, fragmentType)
                .map(fragments -> {
                    if (fragments.isEmpty()) return List.of();

                    List<SoundFragment> shuffled = new ArrayList<>(fragments);
                    Collections.shuffle(shuffled, secureRandom);

                    return shuffled.stream()
                            .limit(quantity)
                            .collect(Collectors.toList());
                });
    }

    private Uni<List<SoundFragment>> getBrandDataCached(String brandName, UUID brandId, PlaylistItemType fragmentType) {
        String cacheKey = brandName + "_" + fragmentType;
        CachedBrandData cached = brandCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {
            return Uni.createFrom().item(cached.fragments);
        }

        if (brandId != null) {
            BrandLogger.logActivity(brandName, "fetching_fragments", "Fetching : %s", fragmentType);
            return repository.getBrandSongsRandomPage(brandId, fragmentType)
                    .flatMap(f -> f.isEmpty()
                            ? repository.getBrandSongs(brandId, fragmentType)
                            : Uni.createFrom().item(f))
                    .map(fragments -> {
                        Collections.shuffle(fragments, secureRandom);
                        brandCache.put(cacheKey, new CachedBrandData(brandId, fragments));
                        return fragments;
                    });
        }

        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID resolvedBrandId = brand.getId();
                    BrandLogger.logActivity(brandName, "fetching_fragments", "Fetching : %s", fragmentType);

                    return repository.getBrandSongsRandomPage(resolvedBrandId, fragmentType)
                            .flatMap(f -> f.isEmpty()
                                    ? repository.getBrandSongs(resolvedBrandId, fragmentType)
                                    : Uni.createFrom().item(f))
                            .map(fragments -> {
                                Collections.shuffle(fragments, secureRandom);
                                brandCache.put(cacheKey, new CachedBrandData(resolvedBrandId, fragments));
                                return fragments;
                            });
                });
    }

    public Uni<List<SoundFragment>> getNextSongByQuery(UUID brandId, PlaylistRequest playlistRequest, int quantity) {
        List<PlaylistItemType> types = playlistRequest.getType();
        if (types == null || types.isEmpty() || types.getFirst() == null) {
            return Uni.createFrom().item(List.of());
        }

        SoundFragmentFilter filter = buildFilterFromStagePlaylist(playlistRequest);
        int fetch = Math.max(quantity * 3, quantity);

        return repository.findByFilter(brandId, filter, fetch)
                .map(fragments -> {
                    if (fragments.isEmpty()) return List.of();

                    Collections.shuffle(fragments, secureRandom);

                    return fragments.stream()
                            .limit(quantity)
                            .collect(Collectors.toList());
                });
    }

    public Uni<List<SoundFragment>> getNextSongFromStaticList(List<UUID> soundFragmentIds, int quantity) {
        if (soundFragmentIds == null || soundFragmentIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        return repository.findByIds(soundFragmentIds)
                .map(fragments -> {
                    if (fragments.isEmpty()) return List.of();

                    Collections.shuffle(fragments, secureRandom);

                    return fragments.stream()
                            .limit(quantity)
                            .collect(Collectors.toList());
                });
    }

    private SoundFragmentFilter buildFilterFromStagePlaylist(PlaylistRequest playlistRequest) {
        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setGenre(playlistRequest.getGenres());
        filter.setLabels(playlistRequest.getLabels());
        filter.setType(playlistRequest.getType());
        filter.setSource(playlistRequest.getSource());
        filter.setSearchTerm(playlistRequest.getSearchTerm());
        return filter;
    }
}
