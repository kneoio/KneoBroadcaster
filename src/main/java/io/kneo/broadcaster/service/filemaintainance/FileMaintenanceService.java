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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class FileMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileMaintenanceService.class);
    private static final int INTERVAL_SECONDS = 360;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(100);
    private static final String ADDRESS_FILE_MAINTENANCE_STATS = "file-maintenance-stats";

    private static class StatsEntry {
        final Instant timestamp;
        final long filesDeleted;
        final long spaceFreedBytes;
        final long directoriesDeleted;

        StatsEntry(long filesDeleted, long spaceFreedBytes, long directoriesDeleted) {
            this.timestamp = Instant.now();
            this.filesDeleted = filesDeleted;
            this.spaceFreedBytes = spaceFreedBytes;
            this.directoriesDeleted = directoriesDeleted;
        }
    }

    private final List<String> outputDirs;
    private final ConcurrentLinkedQueue<StatsEntry> last24hStats = new ConcurrentLinkedQueue<>();
    private Cancellable cleanupSubscription;
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
                broadcasterConfig.getSegmentationOutputDir(),
                broadcasterConfig.getQuarkusFileUploadsPath()
        );
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
        AtomicLong totalFilesDeleted = new AtomicLong(0);
        AtomicLong totalSpaceFreedBytes = new AtomicLong(0);
        AtomicLong totalDirectoriesDeleted = new AtomicLong(0);

        for (String outputDir : outputDirs) {
            try {
                LOGGER.info("Starting cleanup for: {} (tick: {})", outputDir, tick);
                Path outputPath = Path.of(outputDir);

                if (!Files.exists(outputPath)) {
                    LOGGER.debug("Directory does not exist: {}", outputDir);
                    continue;
                }

                Instant threshold = Instant.now().minus(deletionThresholdMinutes, ChronoUnit.MINUTES);

                AtomicLong currentFilesDeleted = new AtomicLong(0);
                AtomicLong currentSpaceFreed = new AtomicLong(0);

                Files.walk(outputPath)
                        .filter(Files::isRegularFile)
                        .filter(path -> isOlderThan(path, threshold))
                        .forEach(path -> deleteFile(path, currentFilesDeleted, currentSpaceFreed));

                totalFilesDeleted.addAndGet(currentFilesDeleted.get());
                totalSpaceFreedBytes.addAndGet(currentSpaceFreed.get());

                AtomicLong currentDirsDeleted = new AtomicLong(0);
                cleanEmptyDirectories(outputPath, currentDirsDeleted);
                totalDirectoriesDeleted.addAndGet(currentDirsDeleted.get());

            } catch (IOException e) {
                LOGGER.error("Error during cleanup of: {}", outputDir, e);
            }
        }

        if (totalFilesDeleted.get() > 0 || totalDirectoriesDeleted.get() > 0) {
            last24hStats.offer(new StatsEntry(
                    totalFilesDeleted.get(),
                    totalSpaceFreedBytes.get(),
                    totalDirectoriesDeleted.get()
            ));
        }

        cleanup24hStats();

        try {
            updateDiskSpace();
        } catch (IOException e) {
            LOGGER.warn("Could not update disk space", e);
            this.totalSpaceBytes = -1;
            this.availableSpaceBytes = -1;
        }

        long files24h = getLast24hFilesDeleted();
        long space24h = getLast24hSpaceFreedBytes();
        long dirs24h = getLast24hDirectoriesDeleted();

        double totalSpaceFreedMB = (double) space24h / (1024 * 1024);
        double totalSpaceMB = (double) totalSpaceBytes / (1024 * 1024);
        double availableSpaceMB = (double) availableSpaceBytes / (1024 * 1024);

        FileMaintenanceStats stats = FileMaintenanceStats.builder()
                .fromService(files24h, space24h, dirs24h)
                .totalSpaceBytes(totalSpaceBytes)
                .availableSpaceBytes(availableSpaceBytes)
                .build();
        eventBus.publish(ADDRESS_FILE_MAINTENANCE_STATS, stats);

        double currentIterationMB = (double) totalSpaceFreedBytes.get() / (1024 * 1024);

        LOGGER.info("Cleanup done. Current iteration: {} files, {} dirs, {} MB freed. " +
                        "Last 24h total: {} files, {} dirs, {} MB. " +
                        "Disk: {} MB total, {} MB available.",
                totalFilesDeleted.get(), totalDirectoriesDeleted.get(), String.format("%.2f", currentIterationMB),
                files24h, dirs24h, String.format("%.2f", totalSpaceFreedMB),
                String.format("%.2f", totalSpaceMB), String.format("%.2f", availableSpaceMB));
    }

    private void cleanup24hStats() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        last24hStats.removeIf(entry -> entry.timestamp.isBefore(cutoff));
    }

    private long getLast24hFilesDeleted() {
        return last24hStats.stream().mapToLong(entry -> entry.filesDeleted).sum();
    }

    private long getLast24hSpaceFreedBytes() {
        return last24hStats.stream().mapToLong(entry -> entry.spaceFreedBytes).sum();
    }

    private long getLast24hDirectoriesDeleted() {
        return last24hStats.stream().mapToLong(entry -> entry.directoriesDeleted).sum();
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
            if (parts.length >= 4) {
                this.totalSpaceBytes = Long.parseLong(parts[3].trim());
                this.availableSpaceBytes = Long.parseLong(parts[2].trim());
                return;
            }
        }
        throw new IOException("Unexpected 'wmic' output format: " + output);
    }

    private void cleanEmptyDirectories(Path directory, AtomicLong directoriesDeleted) throws IOException {
        Files.walk(directory)
                .filter(Files::isDirectory)
                .filter(path -> !path.equals(directory))
                .filter(this::isEmptyDirectory)
                .forEach(path -> deleteDirectory(path, directoriesDeleted));
    }

    private boolean isEmptyDirectory(Path path) {
        try {
            return Files.list(path).count() == 0;
        } catch (IOException e) {
            LOGGER.warn("Could not check directory contents: {}", path, e);
            return false;
        }
    }

    private void deleteDirectory(Path dir, AtomicLong directoriesDeleted) {
        try {
            Files.delete(dir);
            directoriesDeleted.incrementAndGet();
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

    private void deleteFile(Path file, AtomicLong filesDeleted, AtomicLong spaceFreedBytes) {
        try {
            long fileSize = Files.size(file);
            Files.delete(file);
            filesDeleted.incrementAndGet();
            spaceFreedBytes.addAndGet(fileSize);
            LOGGER.debug("Deleted old file: {}", file);
        } catch (IOException e) {
            LOGGER.warn("Could not delete file: {}", file, e);
        }
    }

    public FileMaintenanceStats getStats() {
        cleanup24hStats();

        try {
            updateDiskSpace();
        } catch (IOException e) {
            LOGGER.warn("Could not update disk space for stats", e);
            this.totalSpaceBytes = -1;
            this.availableSpaceBytes = -1;
        }

        return FileMaintenanceStats.builder()
                .fromService(getLast24hFilesDeleted(), getLast24hSpaceFreedBytes(), getLast24hDirectoriesDeleted())
                .totalSpaceBytes(this.totalSpaceBytes)
                .availableSpaceBytes(this.availableSpaceBytes)
                .build();
    }
}