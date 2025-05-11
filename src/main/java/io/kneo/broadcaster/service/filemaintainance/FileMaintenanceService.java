package io.kneo.broadcaster.service.filemaintainance;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.dashboard.FileMaintenanceStats;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@ApplicationScoped
public class FileMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileMaintenanceService.class);
    private static final int INTERVAL_SECONDS = 360;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(100);
    private static final String ADDRESS_FILE_MAINTENANCE_STATS = "file-maintenance-stats";

    private final List<String> outputDirs;
    private Cancellable cleanupSubscription;
    @Getter
    private long filesDeleted;
    @Getter
    private long spaceFreedBytes;
    @Getter
    private long directoriesDeleted;
    @Getter
    private long totalSpaceBytes;
    @Getter
    private long availableSpaceBytes;

    @Inject
    EventBus eventBus;

    long deletionThresholdMinutes = 10;

    @Inject
    public FileMaintenanceService(BroadcasterConfig broadcasterConfig) {
        this.outputDirs = List.of(
                broadcasterConfig.getPathUploads(),
                broadcasterConfig.getPathForMerged(),
                broadcasterConfig.getSegmentationOutputDir()
        );
        this.filesDeleted = 0;
        this.spaceFreedBytes = 0;
        this.directoriesDeleted = 0;
        this.totalSpaceBytes = 0;
        this.availableSpaceBytes = 0;
    }

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Starting initial cleanup of output directories.");
        cleanAllPaths(-1L);
        startCleanupTask();
    }

    private void startCleanupTask() {
        cleanupSubscription = getTicker()
                .onItem().invoke(this::cleanAllPaths)
                .onFailure().invoke(error -> LOGGER.error("Timer error", error))
                .subscribe().with(
                        item -> {},
                        failure -> LOGGER.error("Subscription failed", failure)
                );
    }

    private Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(Duration.ofSeconds(INTERVAL_SECONDS))
                .onOverflow().drop();
    }

    public void stopCleanupTask() {
        if (cleanupSubscription != null) {
            cleanupSubscription.cancel();
        }
    }

    private void cleanAllPaths(Long tick) {
        long totalFilesDeleted = 0;
        long totalSpaceFreedBytes = 0;
        long totalDirectoriesDeleted = 0;

        for (String outputDir : outputDirs) {
            long filesDeletedForPath = 0;
            long spaceFreedBytesForPath = 0;
            long directoriesDeletedForPath = 0;

            try {
                LOGGER.info("Starting cleanup for: {} (tick: {})", outputDir, tick);
                Path outputPath = Path.of(outputDir);

                if (!Files.exists(outputPath)) {
                    LOGGER.debug("Directory does not exist: {}", outputDir);
                    continue;
                }

                Instant threshold = Instant.now().minus(deletionThresholdMinutes, ChronoUnit.MINUTES);

                filesDeleted = 0;
                spaceFreedBytes = 0;
                directoriesDeleted = 0;

                Files.walk(outputPath)
                        .filter(Files::isRegularFile)
                        .filter(path -> isOlderThan(path, threshold))
                        .forEach(this::deleteFile);
                filesDeletedForPath += filesDeleted;
                spaceFreedBytesForPath += spaceFreedBytes;

                cleanEmptyDirectories(outputPath);
                directoriesDeletedForPath += directoriesDeleted;

                totalFilesDeleted += filesDeletedForPath;
                totalSpaceFreedBytes += spaceFreedBytesForPath;
                totalDirectoriesDeleted += directoriesDeletedForPath;

            } catch (IOException e) {
                LOGGER.error("Error during cleanup of: {}", outputDir, e);
            }
        }

        try {
            updateDiskSpace();
        } catch (IOException e) {
            LOGGER.warn("Could not update disk space", e);
            this.totalSpaceBytes = -1;
            this.availableSpaceBytes = -1;
        }

        double totalSpaceFreedMB = (double) totalSpaceFreedBytes / (1024 * 1024);
        double totalSpaceMB = (double) totalSpaceBytes / (1024 * 1024);
        double availableSpaceMB = (double) availableSpaceBytes / (1024 * 1024);

        FileMaintenanceStats stats = FileMaintenanceStats.builder()
                .fromService(totalFilesDeleted, totalSpaceFreedBytes, totalDirectoriesDeleted)
                .totalSpaceBytes(totalSpaceBytes)
                .availableSpaceBytes(availableSpaceBytes)
                .build();
        eventBus.publish(ADDRESS_FILE_MAINTENANCE_STATS, stats);
        LOGGER.info("Cleanup done. Freed: {} MB, Deleted: {} files, {} dirs. Total: {} MB, Available: {} MB.",
                String.format("%.2f", totalSpaceFreedMB), totalFilesDeleted, totalDirectoriesDeleted,
                String.format("%.2f", totalSpaceMB), String.format("%.2f", availableSpaceMB));
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private void updateDiskSpace() throws IOException {
        try {
            if (isWindows()) {
                updateDiskSpaceWindows();
            } else {
                updateDiskSpaceUnix();
            }
        } catch (IOException | NumberFormatException e) {
            // Fallback to Java NIO if command fails
            try {
                Path path = isWindows() ? Path.of("C:") : Path.of("/");
                FileStore store = Files.getFileStore(path);
                this.totalSpaceBytes = store.getTotalSpace();
                this.availableSpaceBytes = store.getUsableSpace();
            } catch (IOException ex) {
                throw new IOException("Failed to get disk space via both command and Java NIO", ex);
            }
        }
    }

    private void updateDiskSpaceUnix() throws IOException {
        Process process = new ProcessBuilder("df", "-B1", "/").start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String[] lines = output.split("\n");

        if (lines.length >= 2) {
            String[] parts = lines[1].split("\\s+");
            if (parts.length >= 4) {
                this.totalSpaceBytes = Long.parseLong(parts[1]);
                this.availableSpaceBytes = Long.parseLong(parts[3]);
                return;
            }
        }
        throw new IOException("Unexpected 'df' output format: " + output);
    }

    private void updateDiskSpaceWindows() throws IOException {
        Process process = new ProcessBuilder("wmic", "logicaldisk", "where", "DeviceID='C:'", "get",
                "Size,FreeSpace", "/format:csv").start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String[] lines = output.split("\n");

        if (lines.length >= 2) {
            String[] parts = lines[1].split(",");
            if (parts.length >= 3) {
                this.totalSpaceBytes = Long.parseLong(parts[1].trim());
                this.availableSpaceBytes = Long.parseLong(parts[2].trim());
                return;
            }
        }
        throw new IOException("Unexpected 'wmic' output format: " + output);
    }

    private void cleanEmptyDirectories(Path directory) throws IOException {
        Files.walk(directory)
                .filter(Files::isDirectory)
                .filter(path -> !path.equals(directory))
                .filter(this::isEmptyDirectory)
                .forEach(this::deleteDirectory);
    }

    private boolean isEmptyDirectory(Path path) {
        try {
            return Files.list(path).count() == 0;
        } catch (IOException e) {
            LOGGER.warn("Could not check directory contents: {}", path, e);
            return false;
        }
    }

    private void deleteDirectory(Path dir) {
        try {
            Files.delete(dir);
            directoriesDeleted++;
            LOGGER.debug("Deleted empty directory: {}", dir);
        } catch (IOException e) {
            LOGGER.warn("Could not delete directory: {}", dir, e);
        }
    }

    private boolean isOlderThan(Path file, Instant threshold) {
        try {
            FileTime lastModifiedTime = Files.getLastModifiedTime(file);
            return lastModifiedTime.toInstant().isBefore(threshold);
        } catch (IOException e) {
            LOGGER.warn("Could not check modification time for file: {}", file, e);
            return false;
        }
    }

    private void deleteFile(Path file) {
        try {
            long fileSize = Files.size(file);
            filesDeleted++;
            spaceFreedBytes += fileSize;
            Files.delete(file);
            LOGGER.debug("Deleted old file: {}", file);
        } catch (IOException e) {
            LOGGER.warn("Could not delete file: {}", file, e);
        }
    }

    public FileMaintenanceStats getStats() {
        try {
            updateDiskSpace();
        } catch (IOException e) {
            LOGGER.warn("Could not update disk space for stats", e);
            this.totalSpaceBytes = -1;
            this.availableSpaceBytes = -1;
        }
        return FileMaintenanceStats.builder()
                .fromService(this.filesDeleted, this.spaceFreedBytes, this.directoriesDeleted)
                .totalSpaceBytes(this.totalSpaceBytes)
                .availableSpaceBytes(this.availableSpaceBytes)
                .build();
    }
}