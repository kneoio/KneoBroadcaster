package io.kneo.broadcaster.model.aiagent;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Voice {
    private String id;
    private String name;
    private TTSEngineType engineType;

    public Voice() {}

    public Voice(String id, String name) {
        this.id = id;
        this.name = name;
    }
}