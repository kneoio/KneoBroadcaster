package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kneo.broadcaster.model.ai.LlmType;
import io.kneo.broadcaster.model.ai.SearchEngineType;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiLiveAgentDTO {
    private String name;
    private String prompt;
    private String messagePrompt;
    private String miniPodcastPrompt;
    private LlmType llmType;
    @JsonProperty("search_engine_type")
    private SearchEngineType searchEngineType = SearchEngineType.PERPLEXITY;
    private LanguageCode preferredLang;
    private String preferredVoice;
    private String secondaryVoice;
    private String secondaryVoiceName;
    private double talkativity;
}