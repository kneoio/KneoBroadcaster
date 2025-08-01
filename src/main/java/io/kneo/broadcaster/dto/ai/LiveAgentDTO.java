package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class LiveAgentDTO {
    private String name;
    private List<String> fillers;
    private String prompt;
    @JsonProperty("decision_prompt")
    private String decisionPrompt = AiPrompts.DECISION_PROMPT;
    private String preferredVoice;
    private double talkativity;

}