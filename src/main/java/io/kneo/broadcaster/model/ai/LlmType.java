package io.kneo.broadcaster.model.ai;

import lombok.Getter;

@Getter
public enum LlmType {
    CLAUDE("claude"),
    OPENAI("openai"),
    GROQ("groq");

    private final String value;

    LlmType(String value) {
        this.value = value;
    }

}
