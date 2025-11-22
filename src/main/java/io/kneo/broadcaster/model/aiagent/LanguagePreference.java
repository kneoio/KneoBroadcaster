package io.kneo.broadcaster.model.aiagent;

import io.kneo.core.localization.LanguageCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LanguagePreference {
    private LanguageCode code;
    private double weight;
}
