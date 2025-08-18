package io.kneo.broadcaster.service.manipulation;


public enum MixProfile {
    MANUAL,
    DJ_CROSSFADE,
    RADIO_STYLE,
    SMOOTH_BLEND,
    QUICK_CUT,
    LONG_FADE,
    OVERLAP_MIX,
    GAPLESS;

    public MixSettings getSettings() {
        return switch (this) {
            case DJ_CROSSFADE -> new MixSettings(
                    8, 0.0f, 0.0f, 1.0f, 1.0f,
                    0.0f, -1.0f, 0.0f, -1.0f, -3.0f, 1
            );
            case RADIO_STYLE -> new MixSettings(
                    0, 1.0f, 1.0f, 1.0f, 1.0f,
                    0.0f, -1.0f, 0.0f, -1.0f, 2.0f, 0
            );
            case SMOOTH_BLEND -> new MixSettings(
                    12, 0.3f, 0.3f, 1.0f, 1.0f,
                    0.0f, -1.0f, 0.0f, -1.0f, -6.0f, -1
            );
            case QUICK_CUT -> new MixSettings(
                    2, 0.0f, 0.0f, 1.0f, 1.0f,
                    0.0f, -1.0f, 0.0f, -1.0f, -10.0f, 0
            );
            case LONG_FADE -> new MixSettings(
                    20, 0.0f, 0.0f, 1.0f, 1.0f,
                    0.0f, -1.0f, 0.0f, -1.0f, -15.0f, -1
            );
            case OVERLAP_MIX -> new MixSettings(
                    15, 0.5f, 0.5f, 0.8f, 0.8f,
                    0.0f, -1.0f, 0.0f, -1.0f, -15.0f, 0
            );
            case GAPLESS -> new MixSettings(
                    0, 1.0f, 1.0f, 1.0f, 1.0f,
                    0.0f, -1.0f, 0.0f, -1.0f, 0.0f, 0
            );
            default -> new MixSettings(); // Manual - default values
        };
    }

    public String getDescription() {
        return switch (this) {
            case MANUAL -> "Manual settings - customize all parameters";
            case DJ_CROSSFADE -> "DJ-style crossfade with exponential curve";
            case RADIO_STYLE -> "Radio-style with 2-second gap, no crossfade";
            case SMOOTH_BLEND -> "Smooth logarithmic blend with partial volumes";
            case QUICK_CUT -> "Quick 2-second crossfade";
            case LONG_FADE -> "Long 20-second fade for ambient music";
            case OVERLAP_MIX -> "Both songs audible during long overlap";
            case GAPLESS -> "No gap, no crossfade - direct connection";
        };
    }
}
