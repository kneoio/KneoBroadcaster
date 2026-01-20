package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.UUID;

public abstract class StreamSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamSupplier.class);

    @Inject
    GeneratedNewsService generatedNewsService;

    protected Uni<List<SoundFragment>> getSongsFromSceneEntry(
            LiveScene activeEntry,
            String slugName,
            UUID brandId,
            SongSupplier songSupplier,
            SoundFragmentService soundFragmentService,
            AiAgent agent,
            IStream stream,
            io.kneo.broadcaster.model.cnst.LanguageTag broadcastingLanguage
    ) {
        return switch (activeEntry.getSourcing()) {
            case QUERY -> {
                PlaylistRequest req = new PlaylistRequest();
                req.setSearchTerm(activeEntry.getSearchTerm());
                req.setGenres(activeEntry.getGenres());
                req.setLabels(activeEntry.getLabels());
                req.setSourcing(activeEntry.getSourcing());
                req.setType(activeEntry.getPlaylistItemTypes());
                int songCount = new Random().nextDouble() < 0.7 ? 1 : 2;
                yield songSupplier.getNextSongByQuery(brandId, req, songCount)
                        .invoke(songs -> {
                            if (songs.isEmpty()) {
                                LOGGER.warn("No songs found for QUERY sourcing - brandId: {}, searchTerm: {}, genres: {}, labels: {}",
                                        brandId, req.getSearchTerm(), req.getGenres(), req.getLabels());
                            }
                        });
            }
            case STATIC_LIST -> {
                List<UUID> fragmentIds = activeEntry.getSoundFragments();
                if (fragmentIds != null && !fragmentIds.isEmpty()) {
                    UUID selectedId = fragmentIds.get(new Random().nextInt(fragmentIds.size()));
                    yield soundFragmentService.getById(selectedId).map(List::of);
                } else {
                    yield songSupplier.getNextSong(slugName, PlaylistItemType.SONG, 1);
                }
            }
            case GENERATED -> {
                List<ScheduledSongEntry> existingSongs = activeEntry.getSongs();
                if (!existingSongs.isEmpty()) {
                    LOGGER.info("Using existing generated news fragment for scene: {}", activeEntry.getSceneTitle());
                    yield Uni.createFrom().item(
                            existingSongs.stream()
                                    .map(ScheduledSongEntry::getSoundFragment)
                                    .toList()
                    );
                }

                List<UUID> fragmentIds = activeEntry.getSoundFragments();
                if (fragmentIds != null && !fragmentIds.isEmpty()) {
                    UUID selectedId = fragmentIds.getFirst();
                    LOGGER.info("Using exist GENERATED fragment for scene: {}", selectedId);
                    yield soundFragmentService.getById(selectedId).map(List::of);
                }

                List<ScenePrompt> prompts = activeEntry.getPrompts();
                if (prompts == null || prompts.isEmpty()) {
                    LOGGER.error("No prompts found for GENERATED scene, falling back to regular songs");
                    yield songSupplier.getNextSong(slugName, PlaylistItemType.SONG, 1);
                }

                ScenePrompt firstPrompt = prompts.getFirst();
                UUID promptId = firstPrompt.getPromptId();

                LOGGER.info("Generating news fragment for prompt: {}", promptId);
                yield generatedNewsService.generateNewsFragment(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage)
                        .map(List::of);
            }
            default -> {
                int songCount = new Random().nextDouble() < 0.7 ? 1 : 2;
                yield songSupplier.getNextSong(slugName, PlaylistItemType.SONG, songCount);
            }
        };
    }
}
