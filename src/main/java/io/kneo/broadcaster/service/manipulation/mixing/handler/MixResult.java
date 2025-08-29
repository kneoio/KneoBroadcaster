package io.kneo.broadcaster.service.manipulation.mixing.handler;

import lombok.Getter;

@Getter
public class MixResult {
    // Getters
    private final String outputPath;
    private final boolean success;
    private final String message;
    private final OutroIntroSettings usedSettings;

    public MixResult(String outputPath, boolean success, String message, OutroIntroSettings settings) {
        this.outputPath = outputPath;
        this.success = success;
        this.message = message;
        this.usedSettings = settings;
    }

}
