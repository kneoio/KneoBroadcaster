package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;

import java.util.List;

public interface ISupplier {

    Uni<List<SoundFragment>> getBrandSongs(String brandSlug, PlaylistItemType playlistItemType, int quantityToFetch);
}