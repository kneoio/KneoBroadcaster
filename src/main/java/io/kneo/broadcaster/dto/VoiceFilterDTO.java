package io.kneo.broadcaster.dto;

import io.kneo.broadcaster.model.aiagent.TTSEngineType;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class VoiceFilterDTO {
    private TTSEngineType engineType;
    private String gender;
    private List<LanguageTag> languages;
    private List<String> labels;
    private String searchTerm;

    public boolean isActivated() {
        return hasAnyFilter();
    }

    private boolean hasAnyFilter() {
        if (engineType != null) {
            return true;
        }
        if (gender != null && !gender.isEmpty()) {
            return true;
        }
        if (languages != null && !languages.isEmpty()) {
            return true;
        }
        if (labels != null && !labels.isEmpty()) {
            return true;
        }
        if (searchTerm != null && !searchTerm.isEmpty()) {
            return true;
        }
        return false;
    }
}
