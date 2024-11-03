package io.kneo.broadcaster.queue;

import io.kneo.broadcaster.stream.HlsPlaylist;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@ApplicationScoped
public class SoundQueueService {
    private static final Logger LOGGER = Logger.getLogger(SoundQueueService.class.getName());
    private static final String SEGMENTS_PATH = "audio/segments";
    private static final int SCAN_INTERVAL = 30; // seconds

    @Getter
    private final HlsPlaylist playlist;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Set<String> processedSegments = ConcurrentHashMap.newKeySet();
    private Path segmentsDirectory;

    public SoundQueueService() {
        this.playlist = new HlsPlaylist();
    }

    @PostConstruct
    void init() {
        try {
            segmentsDirectory = Paths.get(SEGMENTS_PATH);
            if (!Files.exists(segmentsDirectory)) {
                Files.createDirectories(segmentsDirectory);
            }

            // Initial scan
            scanAndUpdatePlaylist();

            // Periodic scanning every 30 seconds
            scheduler.scheduleAtFixedRate(this::scanAndUpdatePlaylist, SCAN_INTERVAL, SCAN_INTERVAL, TimeUnit.SECONDS);
            LOGGER.info("SoundQueueService initialized. Watching directory: " + segmentsDirectory.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.severe("Failed to initialize SoundQueueService: " + e.getMessage());
            throw new RuntimeException("Failed to initialize SoundQueueService", e);
        }
    }

    private void scanAndUpdatePlaylist() {
        try {
            LOGGER.info("Scanning for new segments in: " + segmentsDirectory.toAbsolutePath());
            List<Path> segmentFiles = Files.list(segmentsDirectory)
                    .filter(path -> path.toString().endsWith(".ts"))
                    .sorted()
                    .toList();

            int newSegments = 0;
            for (Path segmentFile : segmentFiles) {
                String fileName = segmentFile.getFileName().toString();
                if (!processedSegments.contains(fileName)) {
                    try {
                        byte[] data = Files.readAllBytes(segmentFile);
                        playlist.addSegment(data);
                        processedSegments.add(fileName);
                        newSegments++;
                        LOGGER.info("Added new segment to playlist: " + fileName);
                    } catch (IOException e) {
                        LOGGER.warning("Failed to read segment file: " + fileName + " - " + e.getMessage());
                    }
                }
            }

            if (newSegments > 0) {
                LOGGER.info("Added " + newSegments + " new segments. Total segments in playlist: " +
                        playlist.getSegmentCount());
            }

        } catch (IOException e) {
            LOGGER.severe("Failed to scan segments directory: " + e.getMessage());
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}