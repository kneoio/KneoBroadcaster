package io.kneo.broadcaster.service.manipulation.mixing.handler;


public enum MixingProfile {
    DJ_SPEECH(15.0f, 12.0f, 0.8f, 1.0f, 0.15f, 0, false, 0.0f),
    DJ_OUTRO(20.0f, 15.0f, 0.9f, 1.0f, 0.05f, 0, false, 0.0f),
    TALK_OVER_SOFT(25.0f, 20.0f, 0.7f, 1.0f, 0.4f, -1, false, 2.0f),
    TALK_OVER_HARD(12.0f, 10.0f, 1.0f, 1.0f, 0.1f, 1, false, 0.0f),
    STATION_ID(8.0f, 6.0f, 1.0f, 1.0f, 0.05f, 0, false, 0.0f),
    STATION_PROMO(10.0f, 8.0f, 0.95f, 1.0f, 0.18f, 0, false, 1.0f),
    SPONSOR_MESSAGE(14.0f, 12.0f, 1.0f, 1.0f, 0.08f, 0, false, 1.5f),
    NEWS_BREAK(16.0f, 14.0f, 1.0f, 1.0f, 0.05f, -1, false, 2.0f),
    WEATHER_BREAK(12.0f, 10.0f, 1.0f, 1.0f, 0.06f, 0, false, 1.0f),
    TRAFFIC_REPORT(10.0f, 8.0f, 1.0f, 1.0f, 0.08f, 1, false, 0.5f),
    SHOW_INTRO(18.0f, 15.0f, 0.85f, 1.0f, 0.25f, -1, true, 3.0f),
    SEGMENT_INTRO(12.0f, 10.0f, 0.8f, 1.0f, 0.3f, 0, false, 1.0f),
    COMMERCIAL_BREAK(8.0f, 6.0f, 1.0f, 1.0f, 0.02f, 1, false, 0.0f),
    POWER_INTRO(20.0f, 18.0f, 0.9f, 1.0f, 0.35f, 1, false, 2.0f),
    QUICK_HIT(5.0f, 4.0f, 1.0f, 1.0f, 0.15f, 1, false, 0.0f),
    DRAMATIC_DROP(15.0f, 12.0f, 1.0f, 1.0f, 0.03f, 1, false, 1.0f),
    TOP_OF_HOUR(16.0f, 14.0f, 1.0f, 1.0f, 0.1f, 0, false, 1.0f),
    HALF_HOUR(10.0f, 8.0f, 1.0f, 1.0f, 0.15f, 0, false, 0.5f),
    ROCK_INTRO(18.0f, 15.0f, 0.85f, 1.0f, 0.3f, 1, false, 2.0f),
    POP_INTRO(14.0f, 12.0f, 0.9f, 1.0f, 0.25f, 0, false, 1.5f),
    CLASSICAL_INTRO(22.0f, 20.0f, 0.75f, 1.0f, 0.4f, -1, true, 4.0f),
    COUNTRY_INTRO(16.0f, 14.0f, 0.8f, 1.0f, 0.28f, -1, false, 2.5f),
    EMERGENCY_BREAK(6.0f, 5.0f, 1.0f, 1.0f, 0.01f, 1, false, 0.0f),
    CONTEST_ANNOUNCEMENT(12.0f, 10.0f, 0.9f, 1.0f, 0.2f, 0, false, 1.0f),
    SPECIAL_EVENT(20.0f, 18.0f, 0.8f, 1.0f, 0.15f, -1, true, 3.0f);

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