package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.localization.LanguageCode;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScriptDTO extends AbstractDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String description;
    private Integer accessLevel = 0;
    private LanguageCode languageCode;
    private List<UUID> labels;
    private List<UUID> brands;
    private List<SceneDTO> scenes;
}
