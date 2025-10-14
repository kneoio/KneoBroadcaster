package io.kneo.broadcaster.dto.ai;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PromptDTO {
    private boolean enabled;
    private String prompt;
}
