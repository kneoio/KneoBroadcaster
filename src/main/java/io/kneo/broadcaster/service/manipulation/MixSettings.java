package io.kneo.broadcaster.service.manipulation;

public class MixSettings {
    public int crossfadeSeconds;
    public float song1MinVolume;
    public float song2MinVolume;
    public float song1Volume;
    public float song2Volume;
    public float song1StartTime;
    public float song1EndTime;
    public float song2StartTime;
    public float song2EndTime;
    public float gapSeconds;
    public int fadeCurve;

    public MixSettings() {
        // Default manual settings
        this(10, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, -1.0f, 0.0f, -1.0f, 0.0f, 0);
    }

    public MixSettings(int crossfadeSeconds, float song1MinVolume, float song2MinVolume,
                       float song1Volume, float song2Volume, float song1StartTime, float song1EndTime,
                       float song2StartTime, float song2EndTime, float gapSeconds, int fadeCurve) {
        this.crossfadeSeconds = crossfadeSeconds;
        this.song1MinVolume = song1MinVolume;
        this.song2MinVolume = song2MinVolume;
        this.song1Volume = song1Volume;
        this.song2Volume = song2Volume;
        this.song1StartTime = song1StartTime;
        this.song1EndTime = song1EndTime;
        this.song2StartTime = song2StartTime;
        this.song2EndTime = song2EndTime;
        this.gapSeconds = gapSeconds;
        this.fadeCurve = fadeCurve;
    }
}

