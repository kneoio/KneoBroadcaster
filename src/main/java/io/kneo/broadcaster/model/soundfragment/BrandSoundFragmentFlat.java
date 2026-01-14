package io.kneo.broadcaster.model.soundfragment;

import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.officeframe.dto.GenreDTO;
import io.kneo.officeframe.dto.LabelDTO;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Setter
@Getter
public class BrandSoundFragmentFlat {
    private UUID id;
    private UUID defaultBrandId;
    private int playedByBrandCount;
    private int ratedByBrandCount;
    private LocalDateTime playedTime;
    private String title;
    private String artist;
    private String album;
    private SourceType source;
    private List<LabelDTO> labels;
    private List<GenreDTO> genres;
    private List<UUID> representedInBrands;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrandSoundFragmentFlat that = (BrandSoundFragmentFlat) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
