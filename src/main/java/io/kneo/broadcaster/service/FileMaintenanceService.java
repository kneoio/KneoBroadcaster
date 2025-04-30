package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.service.stream.FileJanitorTimer;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class FileMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileMaintenanceService.class);

    private final FileJanitorTimer janitorTimer;
    private final String outputDir;
    private Cancellable cleanupSubscription;

    @Inject
    public FileMaintenanceService(FileJanitorTimer janitorTimer,
                                  BroadcasterConfig broadcasterConfig) {
        this.janitorTimer = janitorTimer;
        this.outputDir = broadcasterConfig.getSegmentationOutputDir() != null
                ? broadcasterConfig.getSegmentationOutputDir()
                : Path.of(System.getProperty("java.io.tmpdir"), "hls-segments").toString();
        startCleanupTask();
    }

    private void startCleanupTask() {
        cleanupSubscription = janitorTimer.getTicker()
                .onItem().invoke(() -> clean())
                .onFailure().invoke(error -> LOGGER.error("Timer error", error))
                .subscribe().with(
                        item -> {},
                        failure -> LOGGER.error("Subscription failed", failure)
                );
    }

    public void stopCleanupTask() {
        if (cleanupSubscription != null) {
            cleanupSubscription.cancel();
        }
    }

    private void clean() {
        try {
            LOGGER.info("Starting file cleanup task");
            Path outputPath = Path.of(outputDir);

            if (!Files.exists(outputPath)) {
                LOGGER.debug("Output directory does not exist: {}", outputDir);
                return;
            }

            Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);

            Files.walk(outputPath)
                    .filter(Files::isRegularFile)
                    .filter(path -> isOlderThan(path, oneHourAgo))
                    .forEach(this::deleteFile);

            cleanEmptyDirectories(outputPath);

            LOGGER.info("File cleanup task completed");
        } catch (IOException e) {
            LOGGER.error("Error during file cleanup", e);
        }
    }

    private void cleanEmptyDirectories(Path directory) throws IOException {
        Files.walk(directory)
                .filter(Files::isDirectory)
                .filter(path -> !path.equals(directory)) // Don't delete the root directory
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
            Files.delete(file);
            LOGGER.debug("Deleted old file: {}", file);
        } catch (IOException e) {
            LOGGER.warn("Could not delete file: {}", file, e);
        }
    }
}