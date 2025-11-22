package io.kneo.broadcaster.model.aiagent;

import lombok.Getter;

@Getter
public enum TTSEngineType {
    ELEVENLABS("elevenlabs");

    private final String value;

    TTSEngineType(String value) {
        this.value = value;
    }

}
