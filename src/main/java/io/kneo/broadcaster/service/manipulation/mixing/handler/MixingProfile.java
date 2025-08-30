package io.kneo.broadcaster.service.manipulation.mixing.handler;

public enum MixingProfile {
    DJ_CROSSFADE(8.0f, 6.0f, 1.0f, 1.0f, 0.0f, 1, false, 0.0f),
    RADIO_STYLE(0.0f, 0.0f, 1.0f, 1.0f, 0.2f, 0, false, 2.0f),
    SMOOTH_BLEND(12.0f, 10.0f, 1.0f, 1.0f, 0.2f, -1, false, 0.0f),
    QUICK_CUT(2.0f, 1.0f, 1.0f, 1.0f, 0.1f, 0, false, 0.0f),
    LONG_FADE(20.0f, 18.0f, 1.0f, 1.0f, 0.1f, -1, false, 0.0f),
    OVERLAP_MIX(15.0f, 12.0f, 1.0f, 0.8f, 0.2f, 0, false, 0.0f),
    GAPLESS(0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0, false, 0.0f);

    //curve:  -1 Logarithmic, 0 Linear,1 Exponential;

    public final float fadeStartSeconds;
    public final float introStartSeconds;
    public final float introVolume;
    public final float mainSongVolume;
    public final float fadeToVolume;
    public final int fadeCurve;
    public final boolean autoFadeBasedOnIntro;
    public final float extraFadeTime;

    MixingProfile(float fadeStart, float introStart, float introVol, float mainVol,
                  float fadeToVol, int curve, boolean autoFade, float extraTime) {
        this.fadeStartSeconds = fadeStart;
        this.introStartSeconds = introStart;
        this.introVolume = introVol;
        this.mainSongVolume = mainVol;
        this.fadeToVolume = fadeToVol;
        this.fadeCurve = curve;
        this.autoFadeBasedOnIntro = autoFade;
        this.extraFadeTime = extraTime;
    }
}