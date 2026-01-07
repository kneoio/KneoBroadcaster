package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
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
    private NavigableSet<Scene> scenes =
            new TreeSet<>(Comparator
                    .comparingInt(Scene::getSeqNum)
                    .thenComparing(Scene::getId));

    private LanguageTag languageTag;
    private SceneTimingMode timingMode;
    private List<ScriptVariable> requiredVariables;
}
