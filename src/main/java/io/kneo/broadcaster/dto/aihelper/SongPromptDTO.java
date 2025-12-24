package io.kneo.broadcaster.dto.aihelper;

import io.kneo.broadcaster.model.aiagent.LlmType;
import io.kneo.broadcaster.model.aiagent.PromptType;
import io.kneo.broadcaster.model.aiagent.SearchEngineType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SongPromptDTO {
    private UUID songId;
    private String draft;
    private String prompt;
    private PromptType promptType;
    private LlmType llmType;
    private SearchEngineType searchEngineType;
    private LocalTime startTime;
    private boolean oneTimeRun;
    private boolean podcast;
    private int songDurationSeconds;
    
    public SongPromptDTO(UUID songId, String draft, String prompt, PromptType promptType, 
                         LlmType llmType, SearchEngineType searchEngineType, LocalTime startTime, 
                         boolean oneTimeRun, boolean podcast) {
        this.songId = songId;
        this.draft = draft;
        this.prompt = prompt;
        this.promptType = promptType;
        this.llmType = llmType;
        this.searchEngineType = searchEngineType;
        this.startTime = startTime;
        this.oneTimeRun = oneTimeRun;
        this.podcast = podcast;
        this.songDurationSeconds = 0;
    }
}
