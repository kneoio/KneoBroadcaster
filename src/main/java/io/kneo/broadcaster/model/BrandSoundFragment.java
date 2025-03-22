package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Setter @Getter
public class BrandSoundFragment {
    private UUID id;
    private int playedByBrandCount;
    private LocalDateTime playedTime;
    private SoundFragment soundFragment;

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