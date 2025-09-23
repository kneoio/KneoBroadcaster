package io.kneo.broadcaster.test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class TarsosMerger {

    private static final String MUSIC_DIR = "C:/Users/justa/Music/mixed_test/";
    private static final String INTRO = "C:/Users/justa/Music/Intro_Lumar.wav";
    private static final String CACHE_FILE = "audio_files_cache.txt";
    private static final Set<String> AUDIO_EXTENSIONS =
            Set.of(".wav", ".mp3", ".flac", ".m4a", ".aac", ".ogg");

    public static void main(String[] args) throws Exception {
        List<String> audioFiles = getAudioFiles();

        if (audioFiles.isEmpty()) {
            System.out.println("Need at least 1 audio file in " + MUSIC_DIR);
            return;
        }

        Collections.shuffle(audioFiles);
        String song1 = audioFiles.get(0);
        String outputFile = MUSIC_DIR + "complete_mix_" + System.currentTimeMillis() + ".wav";

        System.out.println("Song:   " + Paths.get(song1).getFileName());
        System.out.println("Intro:  " + Paths.get(INTRO).getFileName());
        System.out.println("Output: " + Paths.get(outputFile).getFileName());
        System.out.println("Merging order: SONG → INTRO");

        WavFile songWav  = convertToWav(song1);
        WavFile introWav = convertToWav(INTRO);

        double introStartSeconds = songWav.durationSeconds - introWav.durationSeconds;
        if (introStartSeconds < 0) introStartSeconds = 0;

        mixWav(songWav.file.getAbsolutePath(), introWav.file.getAbsolutePath(),
                outputFile, 3.0, true, introStartSeconds, 0.2);

        System.out.println("Done! Merged file: " + outputFile);
    }

    private static List<String> getAudioFiles() throws IOException {
        Path cacheFile = Paths.get(CACHE_FILE);

        if (Files.exists(cacheFile)) {
            System.out.println("Loading audio files from cache...");
            return Files.readAllLines(cacheFile);
        }

        System.out.println("Scanning " + MUSIC_DIR + " for audio files...");
        List<String> audioFiles = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(Paths.get(MUSIC_DIR))) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> AUDIO_EXTENSIONS.stream()
                            .anyMatch(ext -> path.toString().toLowerCase().endsWith(ext)))
                    .map(Path::toString)
                    .forEach(audioFiles::add);
        }

        Files.write(cacheFile, audioFiles);
        System.out.println("Cached file list to " + CACHE_FILE);

        return audioFiles;
    }

    private static class WavFile {
        File file;
        double durationSeconds;
        WavFile(File file, double durationSeconds) {
            this.file = file;
            this.durationSeconds = durationSeconds;
        }
    }

    private static WavFile convertToWav(String inputPath) throws Exception {
        if (inputPath.toLowerCase().endsWith(".wav")) {
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(new File(inputPath))) {
                AudioFormat format = ais.getFormat();
                long frames = ais.getFrameLength();
                double duration = (double) frames / format.getFrameRate();
                return new WavFile(new File(inputPath), duration);
            }
        }

        String outputPath = inputPath + "_tmp.wav";
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputPath,
                "-ar", "44100",
                "-ac", "2",
                "-sample_fmt", "s16",
                outputPath
        );
        pb.inheritIO().start().waitFor();

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new File(outputPath))) {
            AudioFormat format = ais.getFormat();
            long frames = ais.getFrameLength();
            double duration = (double) frames / format.getFrameRate();
            return new WavFile(new File(outputPath), duration);
        }
    }


    private static double smoothFade(double progress) {
        return Math.pow(progress, 3.5);  // try 2.0 → 4.0, stronger = steeper
        //return 1.0 / (1.0 + Math.exp(-12 * (progress - 0.5)));
    }


    private static void mixWav(String songFile, String introFile, String outputFile,
                               double fadeLengthSeconds, boolean fadeOutBack,
                               double introStartSeconds, double minDuck) throws Exception {
        File songWav = convertToWav(songFile).file;
        File introWav = convertToWav(introFile).file;

        try (
                AudioInputStream song  = AudioSystem.getAudioInputStream(songWav);
                AudioInputStream intro = AudioSystem.getAudioInputStream(introWav)
        ) {
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    44100, 16, 2, 4, 44100, false
            );

            AudioInputStream convertedSong  = AudioSystem.getAudioInputStream(targetFormat, song);
            AudioInputStream convertedIntro = AudioSystem.getAudioInputStream(targetFormat, intro);

            byte[] songBytes  = convertedSong.readAllBytes();
            byte[] introBytes = convertedIntro.readAllBytes();

            int frameSize = targetFormat.getFrameSize();
            int songFrames  = songBytes.length / frameSize;
            int introFrames = introBytes.length / frameSize;

            int introStartFrame = (int) ((introStartSeconds - 5) * targetFormat.getFrameRate());
            if (introStartFrame < 0) introStartFrame = 0;
            if (introStartFrame > songFrames - introFrames) {
                introStartFrame = songFrames - introFrames;
            }

            int introStartBytes = introStartFrame * frameSize;
            int fadeFrames = (int) (fadeLengthSeconds * targetFormat.getFrameRate());
            int fadeBytes  = fadeFrames * frameSize;

            byte[] mixed = Arrays.copyOf(songBytes, songBytes.length);

            double maxDuck = 1.0;

            for (int i = 0; i < introBytes.length && (i + introStartBytes + 1) < mixed.length; i += 2) {
                int pos = i + introStartBytes;

                int fadeStart = introStartBytes - fadeBytes;
                int fadeEnd   = introStartBytes + introBytes.length;

                double duckFactor;

                if (pos < introStartBytes) {
                    if (pos >= fadeStart) {
                        double progress = (double) (pos - fadeStart) / fadeBytes;
                        double eased = smoothFade(progress);
                        duckFactor = maxDuck - eased * (maxDuck - minDuck);
                    } else {
                        duckFactor = maxDuck;
                    }
                } else if (fadeOutBack && pos >= fadeEnd && pos < fadeEnd + fadeBytes) {
                    double progress = (double) (pos - fadeEnd) / fadeBytes;
                    double eased = smoothFade(progress);
                    duckFactor = minDuck + eased * (maxDuck - minDuck);
                } else if (pos >= introStartBytes && pos < fadeEnd) {
                    duckFactor = minDuck;
                } else {
                    duckFactor = fadeOutBack ? maxDuck : minDuck;
                }

                short s1 = (short) ((mixed[pos+1] << 8) | (mixed[pos] & 0xff));
                short s2 = (short) ((introBytes[i+1] << 8) | (introBytes[i] & 0xff));

                int scaledSong = (int) Math.round(s1 * duckFactor);
                int sum = scaledSong + s2;

                if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
                if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;

                mixed[pos]   = (byte) (sum & 0xff);
                mixed[pos+1] = (byte) ((sum >> 8) & 0xff);
            }

            try (ByteArrayInputStream bais = new ByteArrayInputStream(mixed);
                 AudioInputStream mixedStream = new AudioInputStream(bais, targetFormat, mixed.length / frameSize)) {
                AudioSystem.write(mixedStream, AudioFileFormat.Type.WAVE, new File(outputFile));
            }
        }

        songWav.delete();
       // introWav.delete();
    }


}
