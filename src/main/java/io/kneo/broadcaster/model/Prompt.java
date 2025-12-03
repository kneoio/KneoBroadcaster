package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.aiagent.PromptType;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class Prompt extends SecureDataEntity<UUID> {
    private boolean enabled;
    private String prompt;
    private String description;
    private PromptType promptType;
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
