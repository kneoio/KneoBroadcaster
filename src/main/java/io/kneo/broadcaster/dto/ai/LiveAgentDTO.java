package io.kneo.broadcaster.dto.ai;


import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LiveAgentDTO {
    private String name;
    private String mainPrompt;
    private String preferredVoice;
}