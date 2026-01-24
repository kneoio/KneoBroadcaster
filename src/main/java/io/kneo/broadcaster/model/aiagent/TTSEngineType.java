package io.kneo.broadcaster.model.aiagent;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TTSEngineType {
    ELEVENLABS("elevenlabs"),
    MODELSLAB("modelslab"),
    GOOGLE("google");

    private final String value;

    TTSEngineType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
