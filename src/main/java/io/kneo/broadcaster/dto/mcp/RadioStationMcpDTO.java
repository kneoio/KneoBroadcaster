package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.model.ai.LlmType;
import io.kneo.broadcaster.model.ai.SearchEngineType;
import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class RadioStationMcpDTO extends AbstractDTO {
    private String name;
    private String slugName;
    private String djName;
    private LlmType llmType;
    private String preferredVoice;
    private String secondaryVoice;
    private String secondaryVoiceName;
    private float talkativity;
    private float podcastMode;
    private SearchEngineType searchEngineType;
    private int queueSize;
}