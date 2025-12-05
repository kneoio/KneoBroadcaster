package io.kneo.broadcaster.model.soundfragment;

import io.kneo.broadcaster.service.stream.HlsSegment;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@Setter
@Getter
public class BrandSoundFragment {
    private UUID id;
    private UUID defaultBrandId;
    @Deprecated
    private int queueNum = 1000;
    private int playedByBrandCount;
    private int ratedByBrandCount;
    private LocalDateTime playedTime;
    private SoundFragment soundFragment;
    @Deprecated
    private ConcurrentLinkedQueue<HlsSegment> segments;
    private List<UUID> representedInBrands;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrandSoundFragment that = (BrandSoundFragment) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

}