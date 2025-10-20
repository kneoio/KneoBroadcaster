package io.kneo.broadcaster.dto.ai;

import io.kneo.broadcaster.model.ai.PromptType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PromptDTO {
    private boolean enabled;
    private String prompt;
    private PromptType promptType;
}
