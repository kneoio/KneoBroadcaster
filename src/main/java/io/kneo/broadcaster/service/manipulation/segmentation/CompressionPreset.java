package io.kneo.broadcaster.service.manipulation.segmentation;

public enum CompressionPreset {
    NONE(""),
    LIGHT("acompressor=threshold=-15dB:ratio=2:attack=20:release=250"),
    MEDIUM("acompressor=threshold=-20dB:ratio=4:attack=5:release=100"),
    HEAVY("acompressor=threshold=-25dB:ratio=8:attack=2:release=50:makeup=5");

    private final String ffmpegArgs;

    CompressionPreset(String ffmpegArgs) {
        this.ffmpegArgs = ffmpegArgs;
    }

    public String getFfmpegArgs() {
        return ffmpegArgs;
    }
}

