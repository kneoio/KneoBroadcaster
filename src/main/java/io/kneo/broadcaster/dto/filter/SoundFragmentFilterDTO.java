package io.kneo.broadcaster.dto.filter;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class SoundFragmentFilterDTO implements IFilterDTO {
    private boolean activated = false;

    @NotEmpty(message = "Genres list cannot be empty when provided")
    private List<UUID> genres;

    @NotEmpty(message = "Sources list cannot be empty when provided")
    private List<SourceType> sources;

    @NotEmpty(message = "Types list cannot be empty when provided")
    private List<PlaylistItemType> types;

    @Override
    public boolean isActivated() {
        return activated || hasAnyFilter();
    }

    @Override
    public boolean hasAnyFilter() {
        return (genres != null && !genres.isEmpty()) ||
               (sources != null && !sources.isEmpty()) ||
               (types != null && !types.isEmpty());
    }
}