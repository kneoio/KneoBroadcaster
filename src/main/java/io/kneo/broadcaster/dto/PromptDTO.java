package io.kneo.broadcaster.dto;

import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.localization.LanguageCode;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class PromptDTO extends AbstractDTO {
    private boolean enabled;
    private String prompt;
    private LanguageCode languageCode;
    private boolean master;
    private boolean locked;
    private String title;
    private JsonObject backup;
    private boolean podcast;
    private UUID draftId;
    private UUID masterId;
    private double version;
}
