package io.kneo.broadcaster.service.manipulation.mixing.handler;

import java.util.Random;

public class OutroIntroSettings {
    public float outroFadeStartSeconds;
    public float introStartDelay;
    public float introVolume;
    public float mainSongVolume;
    public float fadeToVolume;
    public int fadeCurve;
    public boolean autoFadeBasedOnIntro;
    public float extraFadeTime;
    public String description;

    public OutroIntroSettings(float outroFadeStartSeconds, float introStartDelay,
                              float introVolume, float mainSongVolume, float fadeToVolume,
                              int fadeCurve, boolean autoFadeBasedOnIntro, float extraFadeTime, String description) {
        this.outroFadeStartSeconds = outroFadeStartSeconds;
        this.introStartDelay = introStartDelay;
        this.introVolume = introVolume;
        this.mainSongVolume = mainSongVolume;
        this.fadeToVolume = fadeToVolume;
        this.fadeCurve = fadeCurve;
        this.autoFadeBasedOnIntro = autoFadeBasedOnIntro;
        this.extraFadeTime = extraFadeTime;
        this.description = description;
    }

    public static OutroIntroSettings fromProfile(MixingProfile profile) {
        return new OutroIntroSettings(
                profile.fadeStartSeconds,
                profile.introStartSeconds,
                profile.introVolume,
                profile.mainSongVolume,
                profile.fadeToVolume,
                profile.fadeCurve,
                profile.autoFadeBasedOnIntro,
                profile.extraFadeTime,
                profile.name()
        );
    }

    public static OutroIntroSettings randomProfile(long seed) {
        MixingProfile[] profiles = MixingProfile.values();
        Random random = new Random(seed);
        MixingProfile selectedProfile = profiles[random.nextInt(profiles.length)];
        return fromProfile(selectedProfile);
    }
}