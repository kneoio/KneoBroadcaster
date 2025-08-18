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
public class SoundFragmentFilterDTO {
    private boolean activated = false;

    @NotEmpty(message = "Genres list cannot be empty when provided")
    private List<UUID> genres;

    @NotEmpty(message = "Sources list cannot be empty when provided")
    private List<SourceType> sources;

    @NotEmpty(message = "Types list cannot be empty when provided")
    private List<PlaylistItemType> types;

    public boolean isActivated() {
        if (activated) {
            return true;
        }
        return hasAnyFilter();
    }

    private boolean hasAnyFilter() {
        if (genres != null && !genres.isEmpty()) {
            return true;
        }
        if (sources != null && !sources.isEmpty()) {
            return true;
        }
        if (types != null && !types.isEmpty()) {
            return true;
        }
        return false;
    }
}