package io.kneo.broadcaster.model.ai;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SimpleReferenceEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
public class AiAgent extends SimpleReferenceEntity {
    private String name;
    private LanguageCode preferredLang;
    private ZoneId timeZone;

    private LlmType llmType;
    private Merger merger;
    private List<Prompt> prompts;
    private List<String> eventPrompts;
    private List<String> messagePrompts;
    private List<String> miniPodcastPrompts;
    private List<Voice> preferredVoice;
    private UUID copilot;
    private List<Tool> enabledTools;
    private double talkativity;
    private double podcastMode;

    public AiAgent() {
        this.preferredVoice = new ArrayList<>();
        this.prompts = new ArrayList<>();
        this.eventPrompts = new ArrayList<>();
        this.messagePrompts = new ArrayList<>();
        this.miniPodcastPrompts = new ArrayList<>();
        this.enabledTools = new ArrayList<>();
    }
}
