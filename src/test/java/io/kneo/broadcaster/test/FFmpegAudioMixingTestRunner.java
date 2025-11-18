package io.kneo.broadcaster.test;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.handler.AudioMixingHandler;
import io.kneo.broadcaster.service.manipulation.mixing.handler.FadeCurve;
import io.kneo.broadcaster.service.manipulation.mixing.handler.MixingProfile;
import io.smallrye.mutiny.Uni;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class FFmpegAudioMixingTestRunner {

    private static final String MUSIC_DIR = "C:/Users/justa/Music/mixed_test/";
    private static final String CACHE_FILE = "audio_files_cache.txt";
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(".wav", ".mp3", ".flac", ".m4a", ".aac", ".ogg");

    public static void main(String[] args) throws Exception {
        List<String> audioFiles = getAudioFiles();

        if (audioFiles.isEmpty()) {
            System.out.println("Need at least 1 audio file in " + MUSIC_DIR);
            return;
        }

        Collections.shuffle(audioFiles);
        String song1 = audioFiles.get(0);
        String intro = "C:/Users/justa/Music/Intro_Lumar.wav";
        String outputFile = MUSIC_DIR + "complete_mix_" + System.currentTimeMillis() + ".wav";

        System.out.println("Selected files:");
        System.out.println("Song 1: " + Paths.get(song1).getFileName());
        System.out.println("Intro: " + Paths.get(intro).getFileName());
        System.out.println("Output: " + Paths.get(outputFile).getFileName());

        BroadcasterConfig config = createConfig();
        AudioMixingHandler handler = getFFmpegAudioMixingHandler(config);

        /*MixingProfile settings = new MixingProfile(
                20.0f,   // outroFadeStartSeconds
                0.0f,   // introStartEarly
                3.0f,    // introVolume
                1.0f,    // mainSongVolume
                0.3f,    // fadeToVolume
                FadeCurve.LINEAR,       // fadeCurve
                false,   // autoFadeBasedOnIntro
                "Random test mixing profile"
        );*/

        MixingProfile settings = new MixingProfile(
                3.0f,   // outroFadeStartSeconds
                15.0f,   // introStartEarly
                3.0f,    // introVolume
                0.0f,    // fadeToVolume
                FadeCurve.LINEAR,       // fadeCurve
                false,   // autoFadeBasedOnIntro
                "Random test mixing profile"
        );

        Uni<String> result = handler.createOutroIntroMix(song1, intro, outputFile, settings, 1.0);
        String output = result.await().indefinitely();
        System.out.println("Done! Complete mix: " + output);
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

        System.out.println("Found " + audioFiles.size() + " audio files");

        // Save to cache
        Files.write(cacheFile, audioFiles);
        System.out.println("Cached file list to " + CACHE_FILE);

        return audioFiles;
    }

    private static BroadcasterConfig createConfig() {
        return new BroadcasterConfig() {
            @Override
            public String getHost() { return "localhost"; }

            @Override
            public String getAgentUrl() {
                return "";
            }

            @Override
            public String getPathUploads() { return MUSIC_DIR; }

            @Override
            public String getPathForMerged() { return MUSIC_DIR; }

            @Override
            public String getSegmentationOutputDir() { return "segmented"; }

            @Override
            public String getPathForExternalServiceUploads() { return "external_uploads"; }

            @Override
            public String getQuarkusFileUploadsPath() { return "/tmp/file-uploads"; }

            @Override
            public String getFfmpegPath() { return "ffmpeg"; }

            @Override
            public String getFfprobePath() { return "ffprobe"; }

            @Override
            public int getAudioSampleRate() { return 44100; }

            @Override
            public String getAudioChannels() { return "stereo"; }

            @Override
            public String getAudioOutputFormat() { return "mp3"; }

            @Override
            public int getMaxSilenceDuration() { return 3600; }

            @Override
            public List<String> getStationWhitelist() {
                return List.of();
            }

            @Override
            public String getAgentApiKey() {
                return "test";
            }
        };
    }

    private static AudioMixingHandler getFFmpegAudioMixingHandler(BroadcasterConfig config) throws IOException, AudioMergeException {
        FFmpegProvider ffmpegProvider = new FFmpegProvider() {
            @Override
            public FFmpeg getFFmpeg() {
                try {
                    return new FFmpeg("ffmpeg");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public FFprobe getFFprobe() {
                try {
                    return new FFprobe("ffprobe");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        return new AudioMixingHandler(config, null, null,null, null, ffmpegProvider);
    }
}