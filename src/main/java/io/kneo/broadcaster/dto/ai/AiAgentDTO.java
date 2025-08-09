package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.ai.LlmType;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.dto.validation.ValidLanguageCode;
import io.kneo.core.localization.LanguageCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgentDTO extends AbstractDTO {
    @NotBlank
    private String name;
    @NotNull
    @ValidLanguageCode(enumClass = LanguageCode.class)
    private LanguageCode preferredLang;
    private LlmType llmType;
    private List<String> prompts;
    private List<String> fillerPrompt;
    private List<VoiceDTO> preferredVoice;
    private List<ToolDTO> enabledTools;
    private double talkativity;
}