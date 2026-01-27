package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.UUID;

public interface ISupplier {


    Uni<List<SoundFragment>> getBrandSongs(String brandSlug, UUID brandId, PlaylistItemType playlistItemType, int quantityToFetch, List<UUID> excludedIds);
}