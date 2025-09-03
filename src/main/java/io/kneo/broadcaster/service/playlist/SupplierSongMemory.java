package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.model.SoundFragment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SupplierSongMemory {
    private final Map<SoundFragment, Integer> playCount = new ConcurrentHashMap<>();

    public void updateLastSelected(List<SoundFragment> selectedFragments) {
        selectedFragments.forEach(fragment ->
                playCount.merge(fragment, 1, Integer::sum)
        );
    }

    public boolean wasPlayed(SoundFragment song) {
        return playCount.containsKey(song);
    }

    public int getPlayCount(SoundFragment song) {
        return playCount.getOrDefault(song, 0);
    }

    public Map<SoundFragment, Integer> getPlayCounts(List<SoundFragment> fragments) {
        Map<SoundFragment, Integer> result = new HashMap<>();
        fragments.forEach(fragment ->
                result.put(fragment, playCount.getOrDefault(fragment, 0))
        );
        return result;
    }

    public void reset() {
        playCount.clear();
    }
}