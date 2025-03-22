package io.kneo.broadcaster.model;

import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

@Getter
public class CurrentFragmentInfo {
    private final UUID fragmentId;
    private final String fragmentName;
    private final long timestamp;

    public CurrentFragmentInfo(UUID fragmentId, String fragmentName, long timestamp) {
        this.fragmentId = fragmentId;
        this.fragmentName = fragmentName;
        this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CurrentFragmentInfo that = (CurrentFragmentInfo) o;
        return Objects.equals(fragmentId, that.fragmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fragmentId);
    }

    @Override
    public String toString() {
        return String.format("CurrentFragmentInfo{fragmentId=%s, fragmentName='%s', timestamp=%d}",
                fragmentId, fragmentName, timestamp);
    }
}