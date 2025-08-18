package io.kneo.broadcaster.service.manipulation;


import jakarta.enterprise.context.ApplicationScoped;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class AudioMixerService {


    public void mergeAudioFiles(String file1Path, String file2Path, String outputPath, MixSettings settings)
            throws IOException, UnsupportedAudioFileException {

        List<Float> samples1 = readAudioFile(file1Path);
        List<Float> samples2 = readAudioFile(file2Path);

        samples1 = processAudioSamples(samples1, settings.song1StartTime, settings.song1EndTime, settings.song1Volume);
        samples2 = processAudioSamples(samples2, settings.song2StartTime, settings.song2EndTime, settings.song2Volume);

        int fadeLength = 44100 * settings.crossfadeSeconds;
        int gapSamples = (int) (44100 * settings.gapSeconds);

        List<Float> mergedSamples = new ArrayList<>(samples1);

        if (gapSamples > 0) {
            for (int i = 0; i < gapSamples; i++) {
                mergedSamples.add(0.0f);
            }
            fadeLength = 0;
        }

        int overlapStart = Math.max(0, mergedSamples.size() - fadeLength);
        for (int i = 0; i < samples2.size(); i++) {
            float sample2 = samples2.get(i);
            int mergedIndex = overlapStart + i;

            if (i < fadeLength && mergedIndex < mergedSamples.size() && gapSamples <= 0) {
                float fadeProgress = (float) i / fadeLength;
                float adjustedProgress = applyFadeCurve(fadeProgress, settings.fadeCurve);
                float song1FadeVolume = 1.0f - adjustedProgress * (1.0f - settings.song1MinVolume);
                float song2FadeVolume = settings.song2MinVolume + adjustedProgress * (1.0f - settings.song2MinVolume);

                float sample1 = mergedSamples.get(mergedIndex) * song1FadeVolume;
                sample2 *= song2FadeVolume;

                // Mix both samples
                mergedSamples.set(mergedIndex, sample1 + sample2);
            } else {
                // Beyond crossfade region - just add second file samples
                mergedSamples.add(sample2);
            }
        }

        writeAudioFile(mergedSamples, outputPath);
    }

    public void mergeAudioFiles(String file1Path, String file2Path, String outputPath, MixProfile profile)
            throws IOException, UnsupportedAudioFileException {
        mergeAudioFiles(file1Path, file2Path, outputPath, profile.getSettings());
    }

    public List<MixProfile> getAvailableProfiles() {
        return List.of(MixProfile.values());
    }

    public String getProfileDescription(MixProfile profile) {
        return profile.getDescription();
    }

    private List<Float> processAudioSamples(List<Float> samples, float startTime, float endTime, float volume) {
        int sampleRate = 44100;
        int startSample = (int) (startTime * sampleRate);
        int endSample = endTime == -1 ? samples.size() : (int) (endTime * sampleRate);
        startSample = Math.max(0, Math.min(startSample, samples.size()));
        endSample = Math.max(startSample, Math.min(endSample, samples.size()));

        List<Float> processed = new ArrayList<>();
        for (int i = startSample; i < endSample; i++) {
            processed.add(samples.get(i) * volume);
        }

        return processed;
    }

    private float applyFadeCurve(float progress, int fadeCurve) {
        switch (fadeCurve) {
            case 1: // Exponential
                return progress * progress;
            case -1: // Logarithmic
                return (float) Math.sqrt(progress);
            default: // Linear
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
            // 16-bit signed PCM
            ByteBuffer bb = ByteBuffer.wrap(buffer, offset, 2);
            if (format.isBigEndian()) {
                bb.order(ByteOrder.BIG_ENDIAN);
            } else {
                bb.order(ByteOrder.LITTLE_ENDIAN);
            }
            short sample = bb.getShort();
            return sample / 32768.0f; // Convert to -1.0 to 1.0 range
        } else if (format.getSampleSizeInBits() == 8) {
            // 8-bit unsigned PCM
            int sample = buffer[offset] & 0xFF;
            return (sample - 128) / 128.0f;
        }
        return 0.0f;
    }

    private void writeAudioFile(List<Float> samples, String outputPath) throws IOException {
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, // encoding
                44100.0f,  // sample rate
                16,        // sample size in bits
                1,         // channels (mono)
                2,         // frame size
                44100.0f,  // frame rate
                false      // big endian
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

        // RIFF header
        out.write("RIFF".getBytes());
        writeInt(out, 36 + dataLength); // File size - 8
        out.write("WAVE".getBytes());

        // fmt chunk
        out.write("fmt ".getBytes());
        writeInt(out, 16); // fmt chunk size
        writeShort(out, 1); // PCM format
        writeShort(out, channels);
        writeInt(out, sampleRate);
        writeInt(out, byteRate);
        writeShort(out, blockAlign);
        writeShort(out, bitsPerSample);

        // data chunk
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