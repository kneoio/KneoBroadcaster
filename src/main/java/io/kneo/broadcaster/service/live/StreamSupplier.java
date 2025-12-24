package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.SceneScheduleEntry;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.UUID;

public abstract class StreamSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamSupplier.class);

    protected Uni<List<SoundFragment>> getSongsFromSceneEntry(
            SceneScheduleEntry activeEntry,
            String slugName,
            UUID brandId,
            SongSupplier songSupplier,
            SoundFragmentService soundFragmentService
    ) {
        return switch (activeEntry.getSourcing()) {
            case QUERY -> {
                PlaylistRequest req = new PlaylistRequest();
                req.setSearchTerm(activeEntry.getSearchTerm());
                req.setGenres(activeEntry.getGenres());
                req.setLabels(activeEntry.getLabels());
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
            default -> {
                int songCount = new Random().nextDouble() < 0.7 ? 1 : 2;
                yield songSupplier.getNextSong(slugName, PlaylistItemType.SONG, songCount);
            }
        };
    }
}
