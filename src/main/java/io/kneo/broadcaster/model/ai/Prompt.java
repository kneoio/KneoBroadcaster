package io.kneo.broadcaster.model.ai;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Prompt {
    private boolean enabled;
    private String prompt;
    private PromptType promptType;
}
