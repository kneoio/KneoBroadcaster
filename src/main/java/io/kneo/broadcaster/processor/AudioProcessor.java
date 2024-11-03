package io.kneo.broadcaster.processor;

import io.kneo.broadcaster.store.AudioFileStore;
import io.kneo.broadcaster.stream.HlsPlaylist;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@ApplicationScoped
public class AudioProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessor.class);
    private static final int SEGMENT_DURATION = 10;

    private final Executor executor = Executors.newSingleThreadExecutor();

    @Inject
    Vertx vertx;

    @Inject
    private AudioFileStore audioFileStore;

    @Inject
    private HlsPlaylist hlsPlaylist;

   // @Scheduled(every="5s")
    void processNewFiles() {
     /*   List<AudioFileStore.AudioFile> unprocessedFiles = audioFileStore.getUnprocessedFiles();
        if (!unprocessedFiles.isEmpty()) {
            // Process files asynchronously
            CompletableFuture.runAsync(() ->
                            unprocessedFiles.forEach(this::processFile),
                    executor
            );
        }*/
    }

  /*  private void processFile(AudioFileStore.AudioFile file) {
        LOGGER.info("Processing file: {}", file.id());

        try {
            List<byte[]> segments = convertToSegments(file.filePath());
            for (byte[] segment : segments) {
                // Use Vert.x to add segments to playlist on the event loop
                vertx.runOnContext(v -> hlsPlaylist.addSegment(segment));
            }

            audioFileStore.markAsProcessed(file.id());
            LOGGER.info("Successfully processed file: {}", file.id());

        } catch (Exception e) {
            LOGGER.error("Failed to process file: {}", file.id(), e);
        }
    }*/

    private List<byte[]> convertToSegments(Path audioFile) throws IOException {
        List<byte[]> segments = new ArrayList<>();
        Path outputDir = Files.createTempDirectory("segments");

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-i", audioFile.toString(),
                    "-af", "apad",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-ar", "44100",
                    "-ac", "2",
                    "-segment_time", String.valueOf(SEGMENT_DURATION),
                    "-f", "segment",
                    "-reset_timestamps", "1",
                    outputDir.resolve("segment%03d.ts").toString()
            );

            Process process = pb.start();

            // Handle FFmpeg output asynchronously
            CompletableFuture.runAsync(() -> logProcess(process));

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Files.list(outputDir)
                        .filter(p -> p.toString().endsWith(".ts"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                byte[] data = Files.readAllBytes(p);
                                if (data.length > 0) {
                                    segments.add(data);
                                    LOGGER.debug("Added segment: {} (size: {} bytes)",
                                            p.getFileName(), data.length);
                                }
                            } catch (IOException e) {
                                LOGGER.error("Failed to read segment: {}", p, e);
                            }
                        });
            } else {
                throw new IOException("FFmpeg failed with exit code: " + exitCode);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("FFmpeg interrupted", e);
        } finally {
            cleanup(outputDir);
        }

        return segments;
    }

    private void logProcess(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOGGER.debug("FFmpeg: {}", line);
            }
        } catch (IOException e) {
            LOGGER.error("Error reading FFmpeg output", e);
        }
    }

    private void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            LOGGER.error("Cleanup failed for: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to cleanup directory: {}", dir, e);
        }
    }
}