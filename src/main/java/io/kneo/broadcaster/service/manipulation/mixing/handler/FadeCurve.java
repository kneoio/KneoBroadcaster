package io.kneo.broadcaster.service.manipulation.mixing.handler;

import lombok.Getter;

@Getter
public enum FadeCurve {
    LINEAR("tri"),      // ← Fixed: "tri" for linear/triangle
    SINUSOIDAL("s");    // ← Correct: "s" for sine

    private final String ffmpegValue;

    FadeCurve(String ffmpegValue) {
        this.ffmpegValue = ffmpegValue;
    }

    public String getFfmpegValue() {
        return ffmpegValue;
    }

    public static FadeCurve getDefault() {
        return SINUSOIDAL;
    }
}