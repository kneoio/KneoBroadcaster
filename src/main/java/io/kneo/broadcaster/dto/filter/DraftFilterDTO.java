package io.kneo.broadcaster.dto.filter;

import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class DraftFilterDTO implements IFilterDTO {
    private boolean activated = false;

    private LanguageCode languageCode;
    private Integer archived;
    private boolean enabled;
    private boolean isMaster;
    private boolean locked;

    @Override
    public boolean isActivated() {
        return activated || hasAnyFilter();
    }

    @Override
    public boolean hasAnyFilter() {
        return languageCode != null || 
               archived != null || 
               enabled || 
               isMaster || 
               locked;
    }
}