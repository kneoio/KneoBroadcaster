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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentMCPTools.class);

    @Inject
    SongSupplier songSupplier;

    @Inject
    RefService refService;

    @Tool("get_brand_sound_fragment")
    @Description("Get a single sound fragment for a specific brand")
    public CompletableFuture<SoundFragmentMcpDTO> getBrandSoundFragments(
            @Parameter("brand") String brandName,
            @Parameter("fragment_type") String fragmentType
    ) {
        return songSupplier.getNextSong(brandName, PlaylistItemType.valueOf(fragmentType))
                .chain(this::mapSoundFragmentToAiDTO)
                .convert().toCompletableFuture();
    }

    private Uni<SoundFragmentMcpDTO> mapSoundFragmentToAiDTO(SoundFragment soundFragment) {
        if (soundFragment == null) {
            return Uni.createFrom().nullItem();
        }

        if (soundFragment.getGenres() == null || soundFragment.getGenres().isEmpty()) {
            return Uni.createFrom().item(SoundFragmentMcpDTO.builder()
                    .id(soundFragment.getId())
                    .title(soundFragment.getTitle())
                    .artist(soundFragment.getArtist())
                    .genres(List.of())
                    .album(soundFragment.getAlbum())
                    .build());
        }

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
                        .build());
    }
}