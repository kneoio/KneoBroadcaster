package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import io.kneo.core.localization.LanguageCode;
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
    private LanguageCode languageCode;
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
        if (languageCode != null) {
            return true;
        }
        return searchTerm != null && !searchTerm.trim().isEmpty();
    }
}
