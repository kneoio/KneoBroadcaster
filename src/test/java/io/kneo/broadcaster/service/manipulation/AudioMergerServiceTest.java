package io.kneo.broadcaster.service.manipulation;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

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


}