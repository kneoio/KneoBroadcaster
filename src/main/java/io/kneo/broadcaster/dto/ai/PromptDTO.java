package io.kneo.broadcaster.dto.ai;

import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.Setter;
import io.vertx.core.json.JsonObject;

@Getter
@Setter
public class PromptDTO {
    private boolean enabled;
    private String prompt;
    private PromptType promptType;
    private LanguageCode languageCode;
    private boolean isMaster;
    private boolean locked;
    private String title;
    private JsonObject backup;
}
