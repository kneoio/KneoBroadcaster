package io.kneo.broadcaster.service.manipulation;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class AudioMixerService {

    private static final Logger LOG = Logger.getLogger(AudioMixerService.class);
    private static final int SAMPLE_RATE = 44100;

    @ConfigProperty(name = "audio.mixer.temp.directory", defaultValue = "/tmp")
    String tempDirectory;

    @ConfigProperty(name = "audio.mixer.default.fade.seconds", defaultValue = "20.0")
    float defaultFadeSeconds;

    public static class OutroIntroSettings {
        public float outroFadeStartSeconds;
        public float introStartDelay;
        public float introVolume;
        public float mainSongVolume;
        public float fadeToVolume;
        public int fadeCurve; // 0=linear, 1=exponential, -1=logarithmic
        public boolean autoFadeBasedOnIntro;
        public float extraFadeTime;

        public OutroIntroSettings() {
            this(20.0f, 15.0f, 1.0f, 1.0f, 0.0f, 0, true, 7.0f);
        }

        public OutroIntroSettings(float outroFadeStartSeconds, float introStartDelay,
                                  float introVolume, float mainSongVolume, float fadeToVolume,
                                  int fadeCurve, boolean autoFadeBasedOnIntro, float extraFadeTime) {
            this.outroFadeStartSeconds = outroFadeStartSeconds;
            this.introStartDelay = introStartDelay;
            this.introVolume = introVolume;
            this.mainSongVolume = mainSongVolume;
            this.fadeToVolume = fadeToVolume;
            this.fadeCurve = fadeCurve;
            this.autoFadeBasedOnIntro = autoFadeBasedOnIntro;
            this.extraFadeTime = extraFadeTime;
        }

        public static OutroIntroSettings defaultSettings() {
            return new OutroIntroSettings();
        }

        public static OutroIntroSettings customSettings(float fadeStart, float introDelay, float fadeToVol) {
            return new OutroIntroSettings(fadeStart, introDelay, 1.0f, 1.0f, fadeToVol, 0, true, 7.0f);
        }
    }

    @Getter
    public static class MixResult {
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

    /**
     * Creates an outro-intro mix with default settings
     */
    public MixResult createOutroIntroMix(String mainSongPath, String introSongPath, String outputPath) {
        return createOutroIntroMix(mainSongPath, introSongPath, outputPath, OutroIntroSettings.defaultSettings());
    }

    /**
     * Creates an outro-intro mix with custom settings
     */
    public MixResult createOutroIntroMix(String mainSongPath, String introSongPath,
                                         String outputPath, OutroIntroSettings settings) {
        try {
            LOG.infof("Starting outro-intro mix: main=%s, intro=%s, output=%s",
                    mainSongPath, introSongPath, outputPath);

            validateAudioFiles(mainSongPath, introSongPath);

            // Read both audio files
            List<Float> mainSongSamples = readAudioFile(mainSongPath);
            List<Float> introSamples = readAudioFile(introSongPath);

            LOG.infof("Loaded main song: %d samples, intro: %d samples",
                    mainSongSamples.size(), introSamples.size());

            // Apply volume to samples
            applyVolume(mainSongSamples, settings.mainSongVolume);
            applyVolume(introSamples, settings.introVolume);

            int fadeStartSample = (int) ((mainSongSamples.size() / (float)SAMPLE_RATE - settings.outroFadeStartSeconds) * SAMPLE_RATE);
            int introStartSample = mainSongSamples.size() - (int)(settings.introStartDelay * SAMPLE_RATE);

            fadeStartSample = Math.max(0, Math.min(fadeStartSample, mainSongSamples.size()));
            introStartSample = Math.max(0, Math.min(introStartSample, mainSongSamples.size()));

            applyOutroFade(mainSongSamples, fadeStartSample, settings.fadeToVolume, settings.fadeCurve);

            List<Float> outputSamples = new ArrayList<>(mainSongSamples);

            for (int i = 0; i < introSamples.size(); i++) {
                int outputIndex = introStartSample + i;

                if (outputIndex < outputSamples.size()) {
                    float mixed = outputSamples.get(outputIndex) + introSamples.get(i);
                    outputSamples.set(outputIndex, Math.max(-1.0f, Math.min(1.0f, mixed)));
                } else {
                    outputSamples.add(introSamples.get(i));
                }
            }

            writeAudioFile(outputSamples, outputPath);

            LOG.infof("Successfully created outro-intro mix: %s", outputPath);
            logSettings(settings);

            return new MixResult(outputPath, true, "Mix created successfully", settings);

        } catch (Exception e) {
            LOG.errorf("Error creating outro-intro mix: %s", e.getMessage());
            return new MixResult(outputPath, false, "Error: " + e.getMessage(), settings);
        }
    }

    /**
     * Adds a song to the end of an existing mix
     */
    public MixResult createOutroIntroThenAdd(String mainSongPath, String introSongPath,
                                             String thirdSongPath, String outputPath) {
        return createOutroIntroThenAdd(mainSongPath, introSongPath, thirdSongPath, outputPath,
                OutroIntroSettings.defaultSettings());
    }

    public MixResult createOutroIntroThenAdd(String mainSongPath, String introSongPath,
                                             String thirdSongPath, String outputPath,
                                             OutroIntroSettings settings) {
        try {
            String tempMixPath = tempDirectory + "/temp_outro_intro_" + System.currentTimeMillis() + ".wav";

            MixResult outroIntroResult = createOutroIntroMix(mainSongPath, introSongPath, tempMixPath, settings);
            if (!outroIntroResult.isSuccess()) {
                return outroIntroResult;
            }

            MixResult finalResult = addSongToEnd(tempMixPath, thirdSongPath, outputPath);

            new File(tempMixPath).delete();

            return finalResult;

        } catch (Exception e) {
            LOG.errorf("Error creating outro-intro then add: %s", e.getMessage());
            return new MixResult(outputPath, false, "Error: " + e.getMessage(), settings);
        }
    }

    private MixResult addSongToEnd(String existingMixPath, String songToAddPath, String outputPath) {
        try {
            LOG.infof("Adding song to end: existing=%s, newSong=%s, output=%s",
                    existingMixPath, songToAddPath, outputPath);

            validateAudioFiles(existingMixPath, songToAddPath);

            List<Float> existingMix = readAudioFile(existingMixPath);
            List<Float> songToAdd = readAudioFile(songToAddPath);

            // Remove silence from END of existing mix
            int endIndex = existingMix.size() - 1;
            for (int i = existingMix.size() - 1; i >= 0; i--) {
                if (Math.abs(existingMix.get(i)) > 0.01f) {
                    endIndex = i;
                    break;
                }
            }

            // Trim the existing mix
            List<Float> trimmedMix = new ArrayList<>(existingMix.subList(0, endIndex + 1));

            // Add the new song
            trimmedMix.addAll(songToAdd);

            writeAudioFile(trimmedMix, outputPath);

            LOG.infof("Successfully added song to end: %s", outputPath);
            return new MixResult(outputPath, true, "Song added successfully", null);

        } catch (Exception e) {
            LOG.errorf("Error adding song to end: %s", e.getMessage());
            return new MixResult(outputPath, false, "Error: " + e.getMessage(), null);
        }
    }

    /**
     * Creates a complete 3-song mix: main + intro + third song
     */
    public MixResult createCompleteMix(String mainSongPath, String introSongPath,
                                       String thirdSongPath, String outputPath) {
        return createCompleteMix(mainSongPath, introSongPath, thirdSongPath, outputPath,
                OutroIntroSettings.defaultSettings());
    }

    /**
     * Creates a complete 3-song mix with custom settings
     */
    public MixResult createCompleteMix(String mainSongPath, String introSongPath,
                                       String thirdSongPath, String outputPath,
                                       OutroIntroSettings settings) {
        try {
            // Create temporary file for intermediate mix
            String tempMixPath = tempDirectory + "/temp_outro_intro_" + System.currentTimeMillis() + ".wav";

            // First create the outro-intro mix
            MixResult outroIntroResult = createOutroIntroMix(mainSongPath, introSongPath, tempMixPath, settings);
            if (!outroIntroResult.isSuccess()) {
                return outroIntroResult;
            }

            // Then add the third song
            MixResult finalResult = addSongToEnd(tempMixPath, thirdSongPath, outputPath);

            // Clean up temp file
            new File(tempMixPath).delete();

            return finalResult;

        } catch (Exception e) {
            LOG.errorf("Error creating complete mix: %s", e.getMessage());
            return new MixResult(outputPath, false, "Error: " + e.getMessage(), settings);
        }
    }

    private void validateAudioFiles(String... filePaths) throws IOException {
        for (String filePath : filePaths) {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new FileNotFoundException("Audio file not found: " + filePath);
            }
            if (!file.canRead()) {
                throw new IOException("Cannot read audio file: " + filePath);
            }
        }
    }

    private void logSettings(OutroIntroSettings settings) {
        LOG.info("=== Outro-Intro Parameters ===");
        LOG.infof("Outro fade starts: %f seconds before end", settings.outroFadeStartSeconds);
        LOG.infof("Intro delay: %f seconds", settings.introStartDelay);
        LOG.infof("Intro volume: %f%%", settings.introVolume * 100);
        LOG.infof("Main song volume: %f%%", settings.mainSongVolume * 100);
        LOG.infof("Fade to volume: %f%%", settings.fadeToVolume * 100);
        LOG.infof("Fade curve: %s", settings.fadeCurve == 0 ? "Linear" :
                settings.fadeCurve == 1 ? "Exponential" : "Logarithmic");
    }

    private void applyVolume(List<Float> samples, float volume) {
        for (int i = 0; i < samples.size(); i++) {
            samples.set(i, samples.get(i) * volume);
        }
    }

    private void applyOutroFade(List<Float> samples, int fadeStartSample, float fadeToVolume, int fadeCurve) {
        int fadeLength = samples.size() - fadeStartSample;
        if (fadeLength <= 0) return;

        for (int i = fadeStartSample; i < samples.size(); i++) {
            float progress = (float)(i - fadeStartSample) / fadeLength;

            // Apply fade curve
            float adjustedProgress = applyFadeCurve(progress, fadeCurve);

            // Calculate volume: start at 1.0, end at fadeToVolume
            float volume = 1.0f - adjustedProgress * (1.0f - fadeToVolume);
            samples.set(i, samples.get(i) * volume);
        }
    }

    private float applyFadeCurve(float progress, int fadeCurve) {
        switch (fadeCurve) {
            case 1:
                return progress * progress;
            case -1:
                return (float) Math.sqrt(progress);
            default:
                return progress;
        }
    }

    private List<Float> readAudioFile(String filePath)
            throws IOException, UnsupportedAudioFileException {
        List<Float> samples = new ArrayList<>();

        try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File(filePath))) {
            AudioFormat format = audioInputStream.getFormat();
            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = audioInputStream.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i += format.getFrameSize()) {
                    float sample = bytesToFloat(buffer, i, format);
                    samples.add(sample);
                }
            }
        }
        return samples;
    }

    private float bytesToFloat(byte[] buffer, int offset, AudioFormat format) {
        if (format.getSampleSizeInBits() == 16) {
            ByteBuffer bb = ByteBuffer.wrap(buffer, offset, 2);
            if (format.isBigEndian()) {
                bb.order(ByteOrder.BIG_ENDIAN);
            } else {
                bb.order(ByteOrder.LITTLE_ENDIAN);
            }
            short sample = bb.getShort();
            return sample / 32768.0f;
        } else if (format.getSampleSizeInBits() == 8) {
            int sample = buffer[offset] & 0xFF;
            return (sample - 128) / 128.0f;
        }
        return 0.0f;
    }

    private void writeAudioFile(List<Float> samples, String outputPath) throws IOException {
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44100.0f,
                16,
                1,
                2,
                44100.0f,
                false
        );

        try (FileOutputStream fos = new FileOutputStream(outputPath);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            writeWavHeader(bos, samples.size() * 2, format);

            byte[] buffer = new byte[2];
            for (float sample : samples) {
                short pcmSample = (short) (sample * 32767);
                buffer[0] = (byte) (pcmSample & 0xFF);
                buffer[1] = (byte) ((pcmSample >> 8) & 0xFF);
                bos.write(buffer);
            }
        }
    }

    private void writeWavHeader(OutputStream out, int dataLength, AudioFormat format)
            throws IOException {
        int sampleRate = (int) format.getSampleRate();
        int channels = format.getChannels();
        int bitsPerSample = format.getSampleSizeInBits();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        out.write("RIFF".getBytes());
        writeInt(out, 36 + dataLength);
        out.write("WAVE".getBytes());

        out.write("fmt ".getBytes());
        writeInt(out, 16);
        writeShort(out, 1);
        writeShort(out, channels);
        writeInt(out, sampleRate);
        writeInt(out, byteRate);
        writeShort(out, blockAlign);
        writeShort(out, bitsPerSample);

        out.write("data".getBytes());
        writeInt(out, dataLength);
    }

    private void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}