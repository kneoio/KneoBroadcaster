package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Script extends SecureDataEntity<UUID> {
    private String name;
    private String slugName;
    private UUID defaultProfileId;
    private String description;
    private Integer accessLevel = 0;
    private Integer archived;
    private List<UUID> labels;
    private List<UUID> brands;
    private List<Scene> scenes;
    private LanguageCode languageCode;
    private SceneTimingMode timingMode = SceneTimingMode.ABSOLUTE_TIME;
    private List<ScriptVariable> requiredVariables;
}
