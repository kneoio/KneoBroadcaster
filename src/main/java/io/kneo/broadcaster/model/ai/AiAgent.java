package io.kneo.broadcaster.model.ai;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SimpleReferenceEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class AiAgent extends SimpleReferenceEntity {
    private String name;
    private LanguageCode preferredLang;
    private ZoneId timeZone;

    @Deprecated
    private String mainPrompt;

    private List<String> prompts;
    private List<String> fillerPrompt;
    private List<Voice> preferredVoice;
    private List<Tool> enabledTools;
    private double talkativity;

    public AiAgent() {
        this.preferredVoice = new ArrayList<>();
    }
}