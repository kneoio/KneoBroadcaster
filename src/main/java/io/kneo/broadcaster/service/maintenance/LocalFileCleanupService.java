package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.file.FileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@ApplicationScoped
public class LocalFileCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFileCleanupService.class);
    private static final Duration TEMP_FILE_MAX_AGE = Duration.ofHours(2);
    private static final Duration ENTITY_FILE_MAX_AGE = Duration.ofDays(1);
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(10);

    private final FileSystem fileSystem;
    private final List<String> managedDirectories;
    private Cancellable cleanupSubscription;

    private final AtomicLong tempFilesDeleted = new AtomicLong(0);
    private final AtomicLong entityFilesDeleted = new AtomicLong(0);
    private final AtomicLong bytesFreed = new AtomicLong(0);
    private LocalDateTime lastCleanupTime;

    @Inject
    public LocalFileCleanupService(BroadcasterConfig config, Vertx vertx) {
        this.fileSystem = vertx.fileSystem();
        String baseUploadPath = config.getPathUploads();
        this.managedDirectories = List.of(
                baseUploadPath + "/sound-fragments-controller",
                baseUploadPath + "/audio-processing",
                baseUploadPath + "/playlist-processing"
        );
    }

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Starting Local File Cleanup Service for directories: {}", managedDirectories);
        startCleanupTask();
    }

    private void startCleanupTask() {
        cleanupSubscription = Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(CLEANUP_INTERVAL)
                .onOverflow().drop()
                .onItem().invoke(this::performCleanup)
                .onFailure().invoke(error -> LOGGER.error("Local file cleanup error", error))
                .subscribe().with(
                        item -> {},
                        failure -> LOGGER.error("Local file cleanup subscription failed", failure)
                );
    }

    public void stopCleanupTask() {
        if (cleanupSubscription != null) {
            cleanupSubscription.cancel();
        }
    }

    private void performCleanup(Long tick) {
        LOGGER.info("Starting local file cleanup (tick: {})", tick);

        long startTime = System.currentTimeMillis();
        long tempDeleted = 0;
        long entityDeleted = 0;
        long bytesFreedSession = 0;

        try {
            for (String directoryPath : managedDirectories) {
                Path uploadPath = Paths.get(directoryPath);
                if (!Files.exists(uploadPath)) {
                    LOGGER.debug("Directory does not exist: {}", uploadPath);
                    continue;
                }

                CleanupResult result = cleanupDirectory(uploadPath).await().atMost(Duration.ofMinutes(5));
                tempDeleted += result.tempFilesDeleted;
                entityDeleted += result.entityFilesDeleted;
                bytesFreedSession += result.bytesFreed;
            }

            tempFilesDeleted.addAndGet(tempDeleted);
            entityFilesDeleted.addAndGet(entityDeleted);
            bytesFreed.addAndGet(bytesFreedSession);
            lastCleanupTime = LocalDateTime.now();

            long duration = System.currentTimeMillis() - startTime;
            double mbFreed = (double) bytesFreedSession / (1024 * 1024);

            LOGGER.info("Local file cleanup completed in {}ms. Temp files deleted: {}, Entity files deleted: {}, Space freed: {} MB",
                    duration, tempDeleted, entityDeleted, mbFreed);

        } catch (Exception e) {
            LOGGER.error("Error during local file cleanup", e);
        }
    }

    private Uni<CleanupResult> cleanupDirectory(Path baseDir) {
        return Uni.createFrom().item(() -> {
            CleanupResult result = new CleanupResult();

            try {
                if (isSpecialTempDirectory(baseDir)) {
                    // Handle audio-processing and playlist-processing directories
                    result.add(cleanupTempDirectory(baseDir));
                } else {
                    // Handle sound-fragments-controller structure with user directories
                    try (Stream<Path> userDirs = Files.list(baseDir)) {
                        for (Path userDir : userDirs.toArray(Path[]::new)) {
                            if (Files.isDirectory(userDir)) {
                                result.add(cleanupUserDirectory(userDir));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to cleanup directory: {}", baseDir, e);
            }

            return result;
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    private boolean isSpecialTempDirectory(Path directory) {
        String dirName = directory.getFileName().toString();
        return "audio-processing".equals(dirName) || "playlist-processing".equals(dirName);
    }

    private CleanupResult cleanupUserDirectory(Path userDir) {
        CleanupResult result = new CleanupResult();

        try (Stream<Path> entityDirs = Files.list(userDir)) {
            for (Path entityDir : entityDirs.toArray(Path[]::new)) {
                if (Files.isDirectory(entityDir)) {
                    String dirName = entityDir.getFileName().toString();

                    if ("temp".equals(dirName)) {
                        result.add(cleanupTempDirectory(entityDir));
                    } else {
                        result.add(cleanupEntityDirectory(entityDir));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to cleanup user directory: {}", userDir, e);
        }

        try {
            if (isDirectoryEmpty(userDir)) {
                Files.delete(userDir);
                LOGGER.debug("Removed empty user directory: {}", userDir);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not remove user directory: {}", userDir);
        }

        return result;
    }

    private CleanupResult cleanupTempDirectory(Path tempDir) {
        CleanupResult result = new CleanupResult();

        try {
            if (isSpecialTempDirectory(tempDir.getParent())) {
                // Direct cleanup for audio-processing and playlist-processing
                cleanupTempFiles(tempDir, result);
            } else {
                // Check if it's a temp subdirectory or direct temp files
                if (Files.exists(tempDir.resolve("temp"))) {
                    cleanupTempFiles(tempDir.resolve("temp"), result);
                } else {
                    cleanupTempFiles(tempDir, result);
                }
            }

            if (isDirectoryEmpty(tempDir)) {
                Files.delete(tempDir);
                LOGGER.debug("Removed empty temp directory: {}", tempDir);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to cleanup temp directory: {}", tempDir, e);
        }

        return result;
    }

    private void cleanupTempFiles(Path directory, CleanupResult result) {
        try (Stream<Path> files = Files.list(directory)) {
            Instant cutoffTime = Instant.now().minus(TEMP_FILE_MAX_AGE);

            for (Path file : files.toArray(Path[]::new)) {
                if (Files.isRegularFile(file)) {
                    try {
                        Instant fileTime = Files.getLastModifiedTime(file).toInstant();
                        if (fileTime.isBefore(cutoffTime)) {
                            long fileSize = Files.size(file);
                            Files.delete(file);
                            result.tempFilesDeleted++;
                            result.bytesFreed += fileSize;
                            LOGGER.debug("Deleted old temp file: {}", file);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to delete temp file: {}", file, e);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to cleanup temp files in directory: {}", directory, e);
        }
    }

    private CleanupResult cleanupEntityDirectory(Path entityDir) {
        CleanupResult result = new CleanupResult();

        try (Stream<Path> files = Files.list(entityDir)) {
            Instant cutoffTime = Instant.now().minus(ENTITY_FILE_MAX_AGE);

            for (Path file : files.toArray(Path[]::new)) {
                if (Files.isRegularFile(file)) {
                    try {
                        Instant fileTime = Files.getLastModifiedTime(file).toInstant();
                        if (fileTime.isBefore(cutoffTime)) {
                            long fileSize = Files.size(file);
                            Files.delete(file);
                            result.entityFilesDeleted++;
                            result.bytesFreed += fileSize;
                            LOGGER.debug("Deleted old entity file: {}", file);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to delete entity file: {}", file, e);
                    }
                }
            }

            if (isDirectoryEmpty(entityDir)) {
                Files.delete(entityDir);
                LOGGER.debug("Removed empty entity directory: {}", entityDir);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to cleanup entity directory: {}", entityDir, e);
        }

        return result;
    }

    private boolean isDirectoryEmpty(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.findFirst().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public Uni<Void> cleanupTempFilesForUser(String username) {
        return Uni.createFrom().item(() -> {
                    Path tempDir = Paths.get(managedDirectories.get(0), username, "temp");
                    if (Files.exists(tempDir)) {
                        cleanupTempDirectory(tempDir);
                        LOGGER.info("Cleaned up temp files for user: {}", username);
                    }
                    return null;
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    public Uni<Void> cleanupEntityFiles(String username, String entityId) {
        return Uni.createFrom().item(() -> {
                    Path entityDir = Paths.get(managedDirectories.get(0), username, entityId);
                    if (Files.exists(entityDir)) {
                        CleanupResult result = cleanupEntityDirectory(entityDir);
                        LOGGER.info("Cleaned up entity files for user: {}, entity: {} - {} files, {} bytes",
                                username, entityId, result.entityFilesDeleted, result.bytesFreed);
                    }
                    return null;
                }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    public Uni<Void> cleanupAfterSuccessfulUpload(String username, String entityId, String fileName) {
        return fileSystem.delete(Paths.get(managedDirectories.get(0), username, entityId, fileName).toString())
                .onItem().invoke(() -> LOGGER.debug("Cleaned up local file after successful upload: {}/{}/{}",
                        username, entityId, fileName))
                .onFailure().invoke(e -> LOGGER.warn("Failed to cleanup local file: {}/{}/{}",
                        username, entityId, fileName, e))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    public CleanupStats getStats() {
        return CleanupStats.builder()
                .tempFilesDeleted(tempFilesDeleted.get())
                .entityFilesDeleted(entityFilesDeleted.get())
                .totalBytesFreed(bytesFreed.get())
                .lastCleanupTime(lastCleanupTime)
                .build();
    }

    private static class CleanupResult {
        long tempFilesDeleted = 0;
        long entityFilesDeleted = 0;
        long bytesFreed = 0;

        void add(CleanupResult other) {
            this.tempFilesDeleted += other.tempFilesDeleted;
            this.entityFilesDeleted += other.entityFilesDeleted;
            this.bytesFreed += other.bytesFreed;
        }
    }

    @Getter
    public static class CleanupStats {
        private final long tempFilesDeleted;
        private final long entityFilesDeleted;
        private final long totalBytesFreed;
        private final LocalDateTime lastCleanupTime;

        private CleanupStats(long tempFilesDeleted, long entityFilesDeleted,
                             long totalBytesFreed, LocalDateTime lastCleanupTime) {
            this.tempFilesDeleted = tempFilesDeleted;
            this.entityFilesDeleted = entityFilesDeleted;
            this.totalBytesFreed = totalBytesFreed;
            this.lastCleanupTime = lastCleanupTime;
        }

        public static CleanupStatsBuilder builder() {
            return new CleanupStatsBuilder();
        }

        public static class CleanupStatsBuilder {
            private long tempFilesDeleted;
            private long entityFilesDeleted;
            private long totalBytesFreed;
            private LocalDateTime lastCleanupTime;

            public CleanupStatsBuilder tempFilesDeleted(long tempFilesDeleted) {
                this.tempFilesDeleted = tempFilesDeleted;
                return this;
            }

            public CleanupStatsBuilder entityFilesDeleted(long entityFilesDeleted) {
                this.entityFilesDeleted = entityFilesDeleted;
                return this;
            }

            public CleanupStatsBuilder totalBytesFreed(long totalBytesFreed) {
                this.totalBytesFreed = totalBytesFreed;
                return this;
            }

            public CleanupStatsBuilder lastCleanupTime(LocalDateTime lastCleanupTime) {
                this.lastCleanupTime = lastCleanupTime;
                return this;
            }

            public CleanupStats build() {
                return new CleanupStats(tempFilesDeleted, entityFilesDeleted, totalBytesFreed, lastCleanupTime);
            }
        }
    }
}