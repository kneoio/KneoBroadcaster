package io.kneo.broadcaster.model.ai;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class Prompt  extends SecureDataEntity<UUID> {
    private boolean enabled;
    private String prompt;
    private PromptType promptType;
    private LanguageCode languageCode;
    private boolean isMaster;
    private boolean locked;

}
