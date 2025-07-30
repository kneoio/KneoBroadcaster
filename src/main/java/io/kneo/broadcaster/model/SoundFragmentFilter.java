package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class SoundFragmentFilter {
    private boolean activated = false;
    private List<String> genres;
    private List<SourceType> sources;
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
        return types != null && !types.isEmpty();
    }
}