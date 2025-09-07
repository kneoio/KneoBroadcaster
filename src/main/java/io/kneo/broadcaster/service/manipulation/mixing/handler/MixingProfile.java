package io.kneo.broadcaster.service.manipulation.mixing.handler;

import java.util.Random;

public class MixingProfile {
    public float outroFadeStartSeconds;
    public float introStartEarly;
    public float introVolume;
    public float fadeToVolume;
    public FadeCurve fadeCurve;
    public boolean autoFadeBasedOnIntro;
    public String description;

    public MixingProfile(float outroFadeStartSeconds, float introStartEarly,
                         float introVolume, float fadeToVolume,
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
                0.0f,    // fadeToVolume  doesnt work , always goes to 0
                FadeCurve.LINEAR,
                false,   // autoFadeBasedOnIntro
                "Variant 1"
        );
    }

    public static MixingProfile getVariant2() {
        return new MixingProfile(
                5.0f,
                15.0f,
                3.0f,
                0.3f,
                FadeCurve.LINEAR,
                false,
                "Variant 2"
        );
    }

    public static MixingProfile getVariant3() {
        return new MixingProfile(
                3.0f,
                10.0f,
                3.0f,
                0.6f,
                FadeCurve.LINEAR,
                false,
                "Variant 3"
        );
    }

    public static MixingProfile randomProfile(long seed) {
        Random random = new Random(seed);
        int variant = random.nextInt(3);

        return switch (variant) {
            case 0 -> getVariant1();
            case 1 -> getVariant2();
            case 2 -> getVariant3();
            default -> getVariant1();
        };
    }
}