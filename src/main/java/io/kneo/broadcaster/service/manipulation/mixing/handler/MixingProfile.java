package io.kneo.broadcaster.service.manipulation.mixing.handler;

public class MixingProfile {
    public float outroFadeStartSeconds;
    public float introStartEarly;
    public float introVolume;
    public float mainSongVolume;
    public float fadeToVolume;
    public FadeCurve fadeCurve;
    public boolean autoFadeBasedOnIntro;
    public String description;

    public MixingProfile(float outroFadeStartSeconds, float introStartEarly,
                         float introVolume, float mainSongVolume, float fadeToVolume,
                         FadeCurve fadeCurve, boolean autoFadeBasedOnIntro, String description) {
        this.outroFadeStartSeconds = outroFadeStartSeconds;
        this.introStartEarly = introStartEarly;
        this.introVolume = introVolume;
        this.fadeToVolume = fadeToVolume;
        this.fadeCurve = fadeCurve;
        this.autoFadeBasedOnIntro = autoFadeBasedOnIntro;
        this.description = description;
    }

    public static MixingProfile getVariant1() {
        return new MixingProfile(
                16.0f,   // outroFadeStartSeconds - changed from 5.0f to 16.0f
                0.0f,   // introStartEarly  if 0 it will measure intro's duration
                1.0f,    // introVolume
                1.0f,    // mainSongVolume  no gain here
                0.0f,    // fadeToVolume  doesnt work , always goes to 0
                FadeCurve.LINEAR,
                false,   // autoFadeBasedOnIntro
                "Variant 1"
        );
    }

    public static MixingProfile randomProfile(long seed) {
        return getVariant1();
    }
}