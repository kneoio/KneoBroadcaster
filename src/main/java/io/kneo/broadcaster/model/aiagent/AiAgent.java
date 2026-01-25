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
    @Deprecated
    private List<Voice> primaryVoice;
    private UUID copilot;
    private TTSSetting ttsSetting;

    public AiAgent() {
        this.preferredLang = new ArrayList<>();
        this.primaryVoice = new ArrayList<>();
    }
}