package io.kneo.broadcaster.test;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.AudioConcatenator;
import io.kneo.broadcaster.service.manipulation.mixing.ConcatenationType;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

public class AudioConcatenatorManualTest {

    private static final String CACHE_FILE = "audio_files_cache.txt";
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(".wav", ".mp3", ".flac", ".m4a", ".aac", ".ogg");

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        props.load(new FileInputStream("src/main/resources/application.properties"));

        String musicDir = props.getProperty("broadcaster.path.uploads", "C:/Users/justa/Music");
        String outputDir = props.getProperty("broadcaster.path.output", musicDir + "/mixed_test");
        String ffmpegPath = "C:/Users/justa/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-7.1-essentials_build/bin/ffmpeg.exe";
        String ffprobePath = "C:/Users/justa/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-7.1-essentials_build/bin/ffprobe.exe";

        List<String> audioFiles = getAudioFiles(musicDir);
        if (audioFiles.isEmpty()) {
            System.out.println("Need at least 2 audio files in " + musicDir);
            return;
        }

        Collections.shuffle(audioFiles);
        String first = audioFiles.get(0);
        String second = audioFiles.size() > 1 ? audioFiles.get(1) : first;
        String output = outputDir + "/concat_mix_" + System.currentTimeMillis() + ".wav";

        System.out.println("Selected files:");
        System.out.println("  First : " + Paths.get(first).getFileName());
        System.out.println("  Second: " + Paths.get(second).getFileName());
        System.out.println("  Output: " + Paths.get(output).getFileName());

        BroadcasterConfig config = createConfig(ffmpegPath, ffprobePath, musicDir, outputDir);
        FFmpegProvider ffmpegProvider = new FFmpegProvider();
        setField(ffmpegProvider, "ffmpeg", new FFmpeg(ffmpegPath));
        setField(ffmpegProvider, "ffprobe", new FFprobe(ffprobePath));

        AudioConcatenator concatenator = new AudioConcatenator(config, ffmpegProvider);

        String result = concatenator
                .concatenate(first, second, output, ConcatenationType.CROSSFADE, 3.0)
                .await().indefinitely();

        System.out.println("✅ Done! File saved to: " + result);
    }

    private static List<String> getAudioFiles(String musicDir) throws IOException {
        Path cacheFile = Paths.get(CACHE_FILE);

        if (Files.exists(cacheFile)) {
            System.out.println("Loading audio files from cache...");
            return Files.readAllLines(cacheFile);
        }

        System.out.println("Scanning " + musicDir + " for audio files...");
        List<String> files = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(Paths.get(musicDir))) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> AUDIO_EXTENSIONS.stream()
                            .anyMatch(ext -> p.toString().toLowerCase().endsWith(ext)))
                    .map(Path::toString)
                    .forEach(files::add);
        }

        Files.write(cacheFile, files);
        System.out.println("Cached " + files.size() + " audio files");
        return files;
    }

    private static BroadcasterConfig createConfig(String ffmpeg, String ffprobe, String upload, String output) {
        return new BroadcasterConfig() {
            public String getHost() { return "localhost"; }
            public String getAgentUrl() { return ""; }
            public String getPathUploads() { return upload; }
            public String getPathForMerged() { return output; }
            public String getSegmentationOutputDir() { return "segmented"; }
            public String getPathForExternalServiceUploads() { return "external_uploads"; }
            public String getQuarkusFileUploadsPath() { return "/tmp/file-uploads"; }
            public String getFfmpegPath() { return ffmpeg; }
            public String getFfprobePath() { return ffprobe; }
            public int getAudioSampleRate() { return 44100; }
            public String getAudioChannels() { return "stereo"; }
            public String getAudioOutputFormat() { return "wav"; }
            public int getMaxSilenceDuration() { return 3600; }
            public List<String> getStationWhitelist() { return List.of(); }

            @Override
            public String getAgentApiKey() {
                return "test";
            }
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
