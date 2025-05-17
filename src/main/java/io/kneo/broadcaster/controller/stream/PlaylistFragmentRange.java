package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.model.SoundFragment;
import lombok.Getter;

import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentNavigableMap;

@Getter
@Deprecated
public class PlaylistFragmentRange {
    private final ConcurrentNavigableMap<Long, HlsSegment> segments;
    private final long start;
    private final long end;
    private final int duration;
    private final SoundFragment fragment;
    private ZonedDateTime staleTime;
    private boolean stale;

    public PlaylistFragmentRange(ConcurrentNavigableMap<Long, HlsSegment> segments, long start, long end, int duration, SoundFragment fragment) {
        this.segments = segments;
        this.start = start;
        this.end = end;
        this.duration = duration;
        this.fragment = fragment;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public void setStale(boolean stale) {
        this.stale = stale;
        staleTime = ZonedDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaylistFragmentRange that = (PlaylistFragmentRange) o;
        return start == that.start && end == that.end && stale == that.stale && segments.equals(that.segments) && fragment.equals(that.fragment);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(segments, start, end, fragment, stale);
    }
}