package io.kneo.broadcaster.model.ai;

import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class AiAgent {
    private String name;
    private LanguageCode preferredLang;
    private String mainPrompt;
    private List<Voice> preferredVoice;
    private List<Tool> enabledTools;

    public AiAgent() {
        this.preferredVoice = new ArrayList<>();
    }

}
