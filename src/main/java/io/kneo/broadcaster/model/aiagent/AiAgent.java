package io.kneo.broadcaster.model.aiagent;

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
    private List<Voice> primaryVoice;
    private UUID copilot;
    private UUID newsReporter;
    private UUID weatherReporter;
    private TTSSetting ttsSetting;

    public AiAgent() {
        this.preferredLang = new ArrayList<>();
        this.primaryVoice = new ArrayList<>();
    }

    public UUID getNewsReporter() {
        return newsReporter;
    }

    public UUID getWeatherReporter() {
        return weatherReporter;
    }
}