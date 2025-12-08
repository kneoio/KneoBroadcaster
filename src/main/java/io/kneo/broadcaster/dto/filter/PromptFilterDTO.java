package io.kneo.broadcaster.dto.filter;

import io.kneo.broadcaster.model.aiagent.PromptType;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class PromptFilterDTO implements IFilterDTO {
    private boolean activated = false;
    private LanguageCode languageCode;
    private PromptType promptType;
    private boolean enabled;
    private boolean master;
    private boolean locked;

    @Override
    public boolean isActivated() {
        return activated || hasAnyFilter();
    }

    @Override
    public boolean hasAnyFilter() {
        return languageCode != null ||
                promptType != null ||
                enabled ||
                master ||
                locked;
    }
}