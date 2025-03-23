package io.kneo.broadcaster.model;

import io.kneo.broadcaster.controller.stream.HlsSegment;
import lombok.Builder;
import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

@Getter
@Builder
public class CurrentFragmentInfo {
    private final long sequence;
    private final UUID fragmentId;
    private final String fragmentName;
    private final long timestamp;
    private final int bitrate;

    public static CurrentFragmentInfo from(long sequence, HlsSegment segment) {
        return CurrentFragmentInfo.builder()
                .sequence(sequence)
                .fragmentId(segment.getSoundFragmentId())
                .fragmentName(segment.getSongName())
                .timestamp(segment.getTimestamp())
                .bitrate(segment.getBitrate())
                .build();
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