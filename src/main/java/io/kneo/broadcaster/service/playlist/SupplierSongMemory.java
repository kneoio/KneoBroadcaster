package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.SoundFragment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SupplierSongMemory {
    private final Set<SoundFragment> playedSongs = new HashSet<>();

    public void updateLastSelected(List<SoundFragment> selectedFragments) {
            playedSongs.addAll(selectedFragments);
    }

    public boolean wasPlayed(SoundFragment song) {
        return playedSongs.contains(song);
    }

    public void reset() {
        playedSongs.clear();
    }
}