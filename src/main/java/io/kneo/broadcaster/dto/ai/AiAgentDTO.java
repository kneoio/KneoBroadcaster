package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.localization.LanguageCode;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgentDTO extends AbstractDTO {
    @NotNull
    private String name;
    private LanguageCode preferredLang;
    @NotNull
    private String mainPrompt;
    private List<String> fillerPrompt;
    private List<VoiceDTO> preferredVoice;
    private List<ToolDTO> enabledTools;
    private double talkativity;
}