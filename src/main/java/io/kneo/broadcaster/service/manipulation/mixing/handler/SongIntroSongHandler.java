package io.kneo.broadcaster.service.manipulation.mixing.handler;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.mcp.AddToQueueMcpDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.job.FFmpegJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SongIntroSongHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SongIntroSongHandler.class);
    private static final int SAMPLE_RATE = 44100;
    private final SoundFragmentRepository repository;
    private final SoundFragmentService soundFragmentService;
    private final String outputDir;
    private final String tempBaseDir;
    private final FFmpegExecutor executor;

    public SongIntroSongHandler(BroadcasterConfig config,
                                SoundFragmentRepository repository,
                                SoundFragmentService soundFragmentService,
                                FFmpegProvider fFmpegProvider) throws IOException {
        this.repository = repository;
        this.soundFragmentService = soundFragmentService;
        this.outputDir = config.getPathForMerged();
        this.tempBaseDir = config.getPathUploads() + "/audio-processing";
        this.executor = new FFmpegExecutor(fFmpegProvider.getFFmpeg());
    }

    public Uni<Boolean> handle(RadioStation radioStation, AddToQueueMcpDTO toQueueDTO) {
        PlaylistManager playlistManager = radioStation.getStreamManager().getPlaylistManager();
        UUID soundFragmentId1 = toQueueDTO.getSoundFragments().get("song1");
        String introSongPath = toQueueDTO.getFilePaths().get("audio1");
        UUID soundFragmentId2 = toQueueDTO.getSoundFragments().get("song2");
        OutroIntroSettings settings = OutroIntroSettings.randomProfile(12345L);
        LOGGER.info("Applied Mixing {}", settings.description);

        return soundFragmentService.getById(soundFragmentId1, SuperUser.build())
                .chain(soundFragment1 -> {
                    return repository.getFirstFile(soundFragment1.getId())
                            .chain(songMetadata1 -> {
                                return songMetadata1.materializeFileStream(tempBaseDir)
                                        .chain(tempPath1 -> {
                                            String tempMixPath = outputDir + "/temp_mix_" + soundFragment1.getSlugName() + "_i_" + System.currentTimeMillis() + ".wav";
                                            return createOutroIntroMixAsync(
                                                    tempPath1.toString(),
                                                    introSongPath,
                                                    tempMixPath,
                                                    settings
                                            );
                                        })
                                        .chain(actualTempMixPath -> {
                                            return soundFragmentService.getById(soundFragmentId2, SuperUser.build())
                                                    .chain(soundFragment2 -> {
                                                        return repository.getFirstFile(soundFragment2.getId())
                                                                .chain(songMetadata2 -> {
                                                                    return songMetadata2.materializeFileStream(tempBaseDir)
                                                                            .chain(tempPath2 -> {
                                                                                String finalMixPath = outputDir + "/final_mix_" + soundFragment1.getSlugName() + "_i_" + soundFragment2.getSlugName() + "_" + System.currentTimeMillis() + ".wav";
                                                                                return addSongToEndAsync(
                                                                                        actualTempMixPath,
                                                                                        tempPath2.toString(),
                                                                                        finalMixPath
                                                                                );
                                                                            });
                                                                });
                                                    });
                                        })
                                        .chain(finalPath -> {
                                            SoundFragment soundFragment = new SoundFragment();
                                            soundFragment.setId(UUID.randomUUID());
                                            soundFragment.setTitle(soundFragment1.getTitle());
                                            soundFragment.setArtist(soundFragment1.getArtist());
                                            FileMetadata fileMetadata = new FileMetadata();
                                            fileMetadata.setTemporaryFilePath(Path.of(finalPath));
                                            soundFragment.setFileMetadataList(List.of(fileMetadata));
                                            return playlistManager.addFragmentToSlice(soundFragment, toQueueDTO.getPriority(),
                                                    radioStation.getBitRate(), toQueueDTO.getMergingMethod());
                                        });
                            });
                });
    }

    private Uni<String> convertToWav(String inputPath) {
        return Uni.createFrom().item(() -> {
            try {
                String outputPath = inputPath.substring(0, inputPath.lastIndexOf('.')) + ".wav";

                FFmpegBuilder builder = new FFmpegBuilder()
                        .setInput(inputPath)
                        .addOutput(outputPath)
                        .addExtraArgs("-ar", "44100")  // Sample rate
                        .addExtraArgs("-ac", "1")      // Mono
                        .addExtraArgs("-acodec", "pcm_s16le")
                        .addExtraArgs("-sample_fmt", "s16")   // Add this line
                        .done();

                FFmpegJob job = executor.createJob(builder);
                job.run();

                LOGGER.info("Successfully converted {} to WAV: {}", inputPath, outputPath);
                return outputPath;

            } catch (Exception e) {
                LOGGER.error("Error converting {} to WAV: {}", inputPath, e.getMessage(), e);
                throw new RuntimeException("Failed to convert MP3 to WAV", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private void validateAudioCompatibility(String... filePaths) throws IOException, UnsupportedAudioFileException {
        List<AudioFormat> formats = new ArrayList<>();

        for (String filePath : filePaths) {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new FileNotFoundException("Audio file not found: " + filePath);
            }
            if (!file.canRead()) {
                throw new IOException("Cannot read audio file: " + filePath);
            }

            try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(file)) {
                AudioFormat format = audioInputStream.getFormat();
                formats.add(format);

                LOGGER.info("File {}: {}Hz, {} channels, {}-bit",
                        filePath, (int)format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits());

                if (format.getSampleSizeInBits() != 8 &&
                        format.getSampleSizeInBits() != 16 &&
                        format.getSampleSizeInBits() != 24 &&
                        format.getSampleSizeInBits() != 32) {
                    throw new UnsupportedAudioFileException(
                            String.format("Unsupported bit depth: %d-bit in file %s. Supported: 8, 16, 24-bit",
                                    format.getSampleSizeInBits(), filePath));
                }

                if (format.getSampleRate() != 44100) {
                    LOGGER.warn("Non-standard sample rate {}Hz in file {}. May cause audio quality issues.",
                            format.getSampleRate(), filePath);
                }
            }
        }

        // Check for format mismatches that might cause issues
        if (formats.size() > 1) {
            AudioFormat first = formats.get(0);
            for (int i = 1; i < formats.size(); i++) {
                AudioFormat current = formats.get(i);

                if (first.getSampleRate() != current.getSampleRate()) {
                    LOGGER.warn("Sample rate mismatch: {}Hz vs {}Hz. Audio may have timing issues.",
                            (int)first.getSampleRate(), (int)current.getSampleRate());
                }

                if (first.getChannels() != current.getChannels()) {
                    LOGGER.warn("Channel mismatch: {} vs {} channels. Will mix to mono.",
                            first.getChannels(), current.getChannels());
                }

                if (first.getSampleSizeInBits() != current.getSampleSizeInBits()) {
                    LOGGER.warn("Bit depth mismatch: {}-bit vs {}-bit. Audio quality may vary.",
                            first.getSampleSizeInBits(), current.getSampleSizeInBits());
                }
            }
        }
    }

    private float[] readAudioFile(String filePath) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File(filePath))) {
            AudioFormat format = audioInputStream.getFormat();

            LOGGER.info("Reading {}: channels={}, sampleRate={}, sampleSize={}, frameSize={}",
                    filePath, format.getChannels(), format.getSampleRate(),
                    format.getSampleSizeInBits(), format.getFrameSize());

            // Calculate approximate sample count for array sizing
            long fileSize = new File(filePath).length();
            int estimatedSamples = (int) (fileSize / format.getFrameSize());
            float[] samples = new float[estimatedSamples];

            byte[] buffer = new byte[4096];
            int bytesRead;
            int sampleIndex = 0;
            int channels = format.getChannels();
            int frameSize = format.getFrameSize();

            while ((bytesRead = audioInputStream.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i += frameSize) {
                    if (i + frameSize > bytesRead) {
                        break;
                    }

                    // Expand array if needed
                    if (sampleIndex >= samples.length) {
                        float[] newSamples = new float[samples.length * 2];
                        System.arraycopy(samples, 0, newSamples, 0, samples.length);
                        samples = newSamples;
                    }

                    if (channels == 1) {
                        samples[sampleIndex] = bytesToFloat(buffer, i, format);
                    } else if (channels == 2) {
                        int sampleSize = format.getSampleSizeInBits() / 8;
                        float leftSample = bytesToFloat(buffer, i, format);
                        float rightSample = bytesToFloat(buffer, i + sampleSize, format);
                        samples[sampleIndex] = (leftSample + rightSample) / 2.0f;
                    } else {
                        samples[sampleIndex] = bytesToFloat(buffer, i, format);
                    }
                    sampleIndex++;
                }
            }

            // Trim to actual size
            if (sampleIndex < samples.length) {
                float[] trimmedSamples = new float[sampleIndex];
                System.arraycopy(samples, 0, trimmedSamples, 0, sampleIndex);
                samples = trimmedSamples;
            }

            LOGGER.info("Read {} samples from {}", samples.length, filePath);
            return samples;
        }
    }

    public Uni<String> addSongToEndAsync(String existingMixPath, String songToAddPath, String outputPath) {
        return checkAndConvertFiles(existingMixPath, songToAddPath)
                .chain(convertedPaths -> {
                    return Uni.createFrom().item(() -> {
                        try {
                            String finalExistingPath = convertedPaths[0];
                            String finalSongPath = convertedPaths[1];

                            LOGGER.info("Adding song to end: existing={}, newSong={}, output={}",
                                    finalExistingPath, finalSongPath, outputPath);

                            validateAudioCompatibility(finalExistingPath, finalSongPath);

                            float[] existingMix = readAudioFile(finalExistingPath);
                            float[] songToAdd = readAudioFile(finalSongPath);

                            LOGGER.info("Loaded existing mix: {} samples, new song: {} samples", existingMix.length, songToAdd.length);

                            // Trim silence from end of existing mix
                            int endIndex = existingMix.length - 1;
                            for (int i = existingMix.length - 1; i >= 0; i--) {
                                if (Math.abs(existingMix[i]) > 0.001f) { // Lower threshold for better detection
                                    endIndex = i;
                                    break;
                                }
                            }

                            // Trim silence from beginning of new song
                            int startIndex = 0;
                            for (int i = 0; i < songToAdd.length; i++) {
                                if (Math.abs(songToAdd[i]) > 0.001f) {
                                    startIndex = i;
                                    break;
                                }
                            }

                            int trimmedMixLength = endIndex + 1;
                            int trimmedSongLength = songToAdd.length - startIndex;

                            float[] finalMix = new float[trimmedMixLength + trimmedSongLength];
                            System.arraycopy(existingMix, 0, finalMix, 0, trimmedMixLength);
                            System.arraycopy(songToAdd, startIndex, finalMix, trimmedMixLength, trimmedSongLength);

                            LOGGER.info("Final mix total samples: {} (trimmed {} samples from end, {} from start)",
                                    finalMix.length, existingMix.length - trimmedMixLength, startIndex);

                            writeAudioFile(finalMix, outputPath);

                            LOGGER.info("Successfully added song to end: {}", outputPath);
                            return outputPath;

                        } catch (Exception e) {
                            LOGGER.error("Error adding song to end: {}", e.getMessage());
                            throw new RuntimeException("Failed to add song to end", e);
                        }
                    }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
                });
    }

    public Uni<String> createOutroIntroMixAsync(String mainSongPath, String introSongPath,
                                                String outputPath, OutroIntroSettings settings) {
        return checkAndConvertFiles(mainSongPath, introSongPath)
                .chain(convertedPaths -> {
                    return Uni.createFrom().item(() -> {
                        try {
                            String finalMainPath = convertedPaths[0];
                            String finalIntroPath = convertedPaths[1];

                            LOGGER.info("Starting outro-intro mix: main={}, intro={}, output={}",
                                    finalMainPath, finalIntroPath, outputPath);

                            validateAudioCompatibility(finalMainPath, finalIntroPath);

                            float[] mainSongSamples = readAudioFile(finalMainPath);
                            float[] introSamples = readAudioFile(finalIntroPath);

                            LOGGER.info("Loaded main song: {} samples, intro: {} samples",
                                    mainSongSamples.length, introSamples.length);

                            applyVolume(mainSongSamples, settings.mainSongVolume);
                            applyVolume(introSamples, settings.introVolume);

                            int fadeStartSample = (int) ((mainSongSamples.length / (float) SAMPLE_RATE - settings.outroFadeStartSeconds) * SAMPLE_RATE);
                            int introStartSample = mainSongSamples.length - (int) (settings.introStartDelay * SAMPLE_RATE);

                            fadeStartSample = Math.max(0, Math.min(fadeStartSample, mainSongSamples.length));
                            introStartSample = Math.max(0, Math.min(introStartSample, mainSongSamples.length));

                            applyOutroFade(mainSongSamples, fadeStartSample, settings.fadeToVolume, settings.fadeCurve);

                            int maxLength = Math.max(mainSongSamples.length, introStartSample + introSamples.length);
                            float[] outputSamples = new float[maxLength];
                            System.arraycopy(mainSongSamples, 0, outputSamples, 0, mainSongSamples.length);

                            for (int i = 0; i < introSamples.length; i++) {
                                int outputIndex = introStartSample + i;
                                if (outputIndex < outputSamples.length) {
                                    float mixed = outputSamples[outputIndex] + introSamples[i];
                                    outputSamples[outputIndex] = Math.max(-1.0f, Math.min(1.0f, mixed));
                                }
                            }

                            writeAudioFile(outputSamples, outputPath);

                            LOGGER.info("Successfully created outro-intro mix: {}", outputPath);
                            logSettings(settings);

                            return outputPath;

                        } catch (Exception e) {
                            LOGGER.error("Error creating outro-intro mix: {}", e.getMessage());
                            throw new RuntimeException("Failed to create outro-intro mix", e);
                        }
                    }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
                });
    }

    private Uni<String[]> checkAndConvertFiles(String... filePaths) {
        if (filePaths.length == 2) {
            String mainPath = filePaths[0];
            String introPath = filePaths[1];

            Uni<String> mainPathUni = needsConversion(mainPath) ?
                    convertToWav(mainPath) : Uni.createFrom().item(mainPath);

            Uni<String> introPathUni = needsConversion(introPath) ?
                    convertToWav(introPath) : Uni.createFrom().item(introPath);

            return Uni.combine().all().unis(mainPathUni, introPathUni)
                    .with((main, intro) -> new String[]{main, intro});
        } else {
            return Uni.createFrom().failure(new IllegalArgumentException("Expected exactly 2 file paths"));
        }
    }

    private boolean needsConversion(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a");
    }

    private void applyVolume(float[] samples, float volume) {
        for (int i = 0; i < samples.length; i++) {
            samples[i] *= volume;
        }
    }

    private void applyOutroFade(float[] samples, int fadeStartSample, float fadeToVolume, int fadeCurve) {
        int fadeLength = samples.length - fadeStartSample;
        if (fadeLength <= 0) return;

        for (int i = fadeStartSample; i < samples.length; i++) {
            float progress = (float) (i - fadeStartSample) / fadeLength;
            float adjustedProgress = applyFadeCurve(progress, fadeCurve);
            float volume = 1.0f - adjustedProgress * (1.0f - fadeToVolume);
            samples[i] *= volume;
        }
    }

    private void writeAudioFile(float[] samples, String outputPath) throws IOException {
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

            writeWavHeader(bos, samples.length * 2, format);

            byte[] buffer = new byte[2];
            for (float sample : samples) {
                short pcmSample = (short) (sample * 32767);
                buffer[0] = (byte) (pcmSample & 0xFF);
                buffer[1] = (byte) ((pcmSample >> 8) & 0xFF);
                bos.write(buffer);
            }
        }
    }

    private float bytesToFloat(byte[] buffer, int offset, AudioFormat format) {
        int sampleSize = format.getSampleSizeInBits();
        boolean bigEndian = format.isBigEndian();

        try {
            if (sampleSize == 16) {
                if (offset + 2 > buffer.length) {
                    LOGGER.warn("Buffer underrun reading 16-bit sample at offset {}", offset);
                    return 0.0f;
                }
                ByteBuffer bb = ByteBuffer.wrap(buffer, offset, 2);
                bb.order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                short sample = bb.getShort();
                return sample / 32768.0f;

            } else if (sampleSize == 24) {
                if (offset + 3 > buffer.length) {
                    LOGGER.warn("Buffer underrun reading 24-bit sample at offset {}", offset);
                    return 0.0f;
                }

                int sample;
                if (bigEndian) {
                    sample = ((buffer[offset] & 0xFF) << 16) |
                            ((buffer[offset + 1] & 0xFF) << 8) |
                            (buffer[offset + 2] & 0xFF);
                } else {
                    sample = (buffer[offset] & 0xFF) |
                            ((buffer[offset + 1] & 0xFF) << 8) |
                            ((buffer[offset + 2] & 0xFF) << 16);
                }

                // Sign extend for 24-bit
                if ((sample & 0x800000) != 0) {
                    sample |= 0xFF000000;
                }
                return sample / 8388608.0f; // 2^23

            } else if (sampleSize == 8) {
                if (offset >= buffer.length) {
                    LOGGER.warn("Buffer underrun reading 8-bit sample at offset {}", offset);
                    return 0.0f;
                }
                int sample = buffer[offset] & 0xFF;
                return (sample - 128) / 128.0f;

            } else {
                LOGGER.error("Unsupported sample size: {} bits", sampleSize);
                return 0.0f;
            }

        } catch (Exception e) {
            LOGGER.error("Error converting bytes to float at offset {}: {}", offset, e.getMessage());
            return 0.0f;
        }
    }

    private void applyVolume(List<Float> samples, float volume) {
        samples.replaceAll(aFloat -> aFloat * volume);
    }

    private void applyOutroFade(List<Float> samples, int fadeStartSample, float fadeToVolume, int fadeCurve) {
        int fadeLength = samples.size() - fadeStartSample;
        if (fadeLength <= 0) return;

        for (int i = fadeStartSample; i < samples.size(); i++) {
            float progress = (float) (i - fadeStartSample) / fadeLength;
            float adjustedProgress = applyFadeCurve(progress, fadeCurve);
            float volume = 1.0f - adjustedProgress * (1.0f - fadeToVolume);
            samples.set(i, samples.get(i) * volume);
        }
    }

    private float applyFadeCurve(float progress, int fadeCurve) {
        return switch (fadeCurve) {
            case 1 -> progress * progress;
            case -1 -> (float) Math.sqrt(progress);
            default -> progress;
        };
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

    private void logSettings(OutroIntroSettings settings) {
        LOGGER.info("=== Outro-Intro Parameters ===");
        LOGGER.info("Outro fade starts: {} seconds before end", settings.outroFadeStartSeconds);
        LOGGER.info("Intro delay: {} seconds", settings.introStartDelay);
        LOGGER.info("Intro volume: {}%%", settings.introVolume * 100);
        LOGGER.info("Main song volume: {}%%", settings.mainSongVolume * 100);
        LOGGER.info("Fade to volume: {}%%", settings.fadeToVolume * 100);
        LOGGER.info("Fade curve: {}", settings.fadeCurve == 0 ? "Linear" :
                settings.fadeCurve == 1 ? "Exponential" : "Logarithmic");
    }
}