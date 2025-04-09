package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.model.SoundFragment;
import java.util.concurrent.ConcurrentNavigableMap;

public record PlaylistFragmentRange(ConcurrentNavigableMap<Long, HlsSegment> segments, long start, long end, SoundFragment fragment) {

    public boolean isEmpty() {
        return segments.isEmpty();
    }
}