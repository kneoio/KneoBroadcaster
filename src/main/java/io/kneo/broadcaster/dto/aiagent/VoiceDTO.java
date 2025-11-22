package io.kneo.broadcaster.dto.aiagent;

import io.kneo.broadcaster.model.aiagent.TTSEngineType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VoiceDTO {
    private String id;
    private String name;
    private TTSEngineType engineType;
}
