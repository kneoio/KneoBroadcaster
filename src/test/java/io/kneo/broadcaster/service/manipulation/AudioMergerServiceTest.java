package io.kneo.broadcaster.service.manipulation;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class AudioMergerServiceTest {

    @Inject
    AudioMergerService audioMergerService;

    @ConfigProperty(name = "broadcaster.ffmpeg.path")
    String ffmpegPath;

    private Path testDir;
    private Path testFile1;
    private Path testFile2;
    private Path outputDir;

    @BeforeEach
    public void setup() throws IOException {
        this.testDir = Paths.get(System.getProperty("java.io.tmpdir"), "audio-merger-test-" + System.currentTimeMillis());
        Files.createDirectories(testDir);
        this.outputDir = Paths.get(System.getProperty("user.dir"), "target", "test-outputs");
        Files.createDirectories(outputDir);
        testFile1 = testDir.resolve("test1.mp3");
        testFile2 = testDir.resolve("test2.mp3");

        Files.copy(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("test-files/test1.mp3")),
                testFile1,
                StandardCopyOption.REPLACE_EXISTING
        );

        Files.copy(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("test-files/test2.mp3")),
                testFile2,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 5})
    public void testMergeAudioFiles(int silenceDuration) throws IOException {
        Path result = audioMergerService.mergeAudioFiles(testFile1, testFile2, silenceDuration);

        assertNotNull(result, "Result should not be null");
        assertTrue(Files.exists(result), "Result file should exist");
        assertTrue(result.toString().endsWith(".mp3"), "Result should be an MP3 file");

        Path savedOutput = outputDir.resolve("merged_with_" + silenceDuration + "s_silence.mp3");
        Files.copy(result, savedOutput, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Output saved for listening at: " + savedOutput.toAbsolutePath());

        long resultSize = Files.size(result);
        long file1Size = Files.size(testFile1);

        assertTrue(resultSize > file1Size, "Merged file should be larger than first file");

        if (silenceDuration > 0) {
            Path resultWithoutSilence = audioMergerService.mergeAudioFiles(testFile1, testFile2, 0);
            long withoutSilenceSize = Files.size(resultWithoutSilence);

            assertTrue(resultSize > withoutSilenceSize,
                    "Merged file with " + silenceDuration + "s silence should be larger than without silence");
        }
    }

    @Test
    public void testMergeAudioFilesWithNonexistentFiles() {
        Path nonExistentFile = testDir.resolve("nonexistent.mp3");
        Path result = audioMergerService.mergeAudioFiles(nonExistentFile, testFile2, 0);

        assertNull(result, "Result should be null when input file doesn't exist");
    }
}