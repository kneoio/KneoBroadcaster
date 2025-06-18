package io.kneo.broadcaster.dto.ai;

import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AiAgentDTO extends AbstractDTO {
    private String name;
    private LanguageCode preferredLang;
    private String mainPrompt;
    private List<String> fillerPrompt;
    private List<VoiceDTO> preferredVoice;
    private List<ToolDTO> enabledTools;
    private double talkativity;
}