package io.kneo.broadcaster.dto.ai;


import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class LiveAgentDTO {
    private String name;
    private List<String> fillers;
    private String mainPrompt;
    private String preferredVoice;
}