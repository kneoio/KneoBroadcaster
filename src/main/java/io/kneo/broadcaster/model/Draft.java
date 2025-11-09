package io.kneo.broadcaster.model;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.DataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Draft extends DataEntity<UUID> {
    private String draftType;
    private String title;
    private String content;
    private LanguageCode languageCode;
    private Integer archived;
    private boolean enabled;
    private boolean isMaster;
    private boolean locked;
    private UUID masterId;
}
