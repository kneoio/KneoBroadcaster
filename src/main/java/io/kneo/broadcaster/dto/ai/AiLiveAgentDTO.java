package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kneo.broadcaster.model.ai.LlmType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AiLiveAgentDTO {
    private String name;
    private List<String> fillers;
    private String prompt;
    private LlmType llmType;

    @JsonProperty("decision_prompt")
    private String decisionPrompt = AiPrompts.getDecisionPrompt();

    private String preferredVoice;
    private double talkativity;
}