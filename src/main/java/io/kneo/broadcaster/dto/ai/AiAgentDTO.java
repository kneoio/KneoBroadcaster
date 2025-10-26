package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgentDTO extends AbstractDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String preferredLang;
    @NotBlank
    private String llmType;
    private String searchEngineType;
    private MergerDTO merger;
    private List<PromptDTO> prompts;
    private List<String> eventPrompts;
    private List<String> messagePrompts;
    private List<String> miniPodcastPrompts;
    private List<VoiceDTO> preferredVoice;
    private UUID copilot;
    private List<ToolDTO> enabledTools;
    private double talkativity;
    private double podcastMode;
}
