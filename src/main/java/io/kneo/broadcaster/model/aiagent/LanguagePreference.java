package io.kneo.broadcaster.model.aiagent;

import com.semantyca.core.model.cnst.LanguageTag;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class LanguagePreference {
    private LanguageTag languageTag;
    private double weight;
}
