package io.kneo.broadcaster.model.aiagent;

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

}
