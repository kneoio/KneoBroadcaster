package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.smallrye.mutiny.Uni;

public interface IPlaylist {
    void startSelfManaging();
    Uni<Boolean> addFragmentToSlice(BrandSoundFragment brandSoundFragment, long bitRate);
    LiveSoundFragment getNextFragment();
    PlaylistManagerStats getStats();
}
