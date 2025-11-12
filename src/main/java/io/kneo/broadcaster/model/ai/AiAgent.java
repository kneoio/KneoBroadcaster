package io.kneo.broadcaster.model.ai;

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
    private List<LanguagePreference> preferredLang;
    private ZoneId timeZone;

    private LlmType llmType;
    private SearchEngineType searchEngineType = SearchEngineType.PERPLEXITY;
    private Merger merger;
    private List<Voice> preferredVoice;
    private UUID copilot;
    private double talkativity;
    private double podcastMode;

    public AiAgent() {
        this.preferredLang = new ArrayList<>();
        this.preferredVoice = new ArrayList<>();
    }
}