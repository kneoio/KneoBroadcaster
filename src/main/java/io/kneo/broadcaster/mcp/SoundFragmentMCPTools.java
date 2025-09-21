package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.mcp.SoundFragmentMcpDTO;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.service.RefService;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.core.localization.LanguageCode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentMCPTools.class);
    private static final Random RANDOM = new Random();

    @Inject
    SongSupplier songSupplier;

    @Inject
    RefService refService;

    @Tool("get_brand_sound_fragment")
    @Description("Get sound fragments for a specific brand")
    public CompletableFuture<List<SoundFragmentMcpDTO>> getBrandSoundFragments(
            @Parameter("brand") String brandName,
            @Parameter("fragment_type") String fragmentType
    ) {
        int count = RANDOM.nextDouble() < 0.2 ? 2 : 1;
        return songSupplier.getNextSong(brandName, PlaylistItemType.valueOf(fragmentType), count)
                .chain(this::mapSoundFragmentsToAiDTO)
                .convert().toCompletableFuture();
    }

    private Uni<List<SoundFragmentMcpDTO>> mapSoundFragmentsToAiDTO(List<SoundFragment> soundFragments) {
        if (soundFragments == null || soundFragments.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        return Uni.join().all(
                soundFragments.stream()
                        .map(this::mapSoundFragmentToAiDTO)
                        .collect(Collectors.toList())
        ).andFailFast();
    }

    private Uni<SoundFragmentMcpDTO> mapSoundFragmentToAiDTO(SoundFragment soundFragment) {
        return Uni.join().all(
                        soundFragment.getGenres().stream()
                                .map(genreId -> refService.getById(genreId)
                                        .map(genre -> genre.getLocalizedName().get(LanguageCode.en)))
                                .collect(Collectors.toList())
                ).andFailFast()
                .map(genreNames -> SoundFragmentMcpDTO.builder()
                        .id(soundFragment.getId())
                        .title(soundFragment.getTitle())
                        .artist(soundFragment.getArtist())
                        .genres(genreNames)
                        .album(soundFragment.getAlbum())
                        .description(soundFragment.getDescription())
                        .build());
    }
}