package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class ScriptFilter {
    private boolean activated = false;
    private List<UUID> labels;
    private SceneTimingMode timingMode;
    private LanguageTag languageTag;
    private String searchTerm;

    public boolean isActivated() {
        if (activated) {
            return true;
        }
        return hasAnyFilter();
    }

    private boolean hasAnyFilter() {
        if (labels != null && !labels.isEmpty()) {
            return true;
        }
        if (timingMode != null) {
            return true;
        }
        if (languageTag != null) {
            return true;
        }
        return searchTerm != null && !searchTerm.trim().isEmpty();
    }
}
