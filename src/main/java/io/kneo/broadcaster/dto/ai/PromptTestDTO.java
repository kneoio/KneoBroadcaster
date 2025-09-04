package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.ai.LlmType;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromptTestDTO {
    @NotBlank
    private String prompt;
    private LlmType llmType;
    private Map<String, String> variables;
}