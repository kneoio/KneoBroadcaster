package io.kneo.broadcaster.service.external.digitalocean;

import io.kneo.broadcaster.config.DOConfig;
import io.kneo.broadcaster.dto.dashboard.SpacesOrphanCleanupStats;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class SpacesFileOrphanCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpacesFileOrphanCleanup.class);
    private static final int INTERVAL_SECONDS = 3600;
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);
    private static final String ADDRESS_ORPHAN_CLEANUP_STATS = "spaces-orphan-cleanup-stats";
    private LocalDateTime lastCleanupTime;
    private String lastError;
    private static final int BATCH_SIZE = 1000;

    private final DOConfig doConfig;
    private final PgPool pgPool;
    private final EventBus eventBus;
    private S3Client s3Client;
    private Cancellable cleanupSubscription;

    @Getter
    private final AtomicLong orphanFilesDeleted = new AtomicLong(0);
    @Getter
    private final AtomicLong spaceFreedBytes = new AtomicLong(0);
    @Getter
    private final AtomicLong totalFilesScanned = new AtomicLong(0);

    @Inject
    public SpacesFileOrphanCleanup(DOConfig doConfig, PgPool pgPool, EventBus eventBus) {
        this.doConfig = doConfig;
        this.pgPool = pgPool;
        this.eventBus = eventBus;
    }

    void onStart(@Observes StartupEvent event) {
        initializeS3Client();
        LOGGER.info("Starting Spaces file orphan cleanup service.");
        startCleanupTask();
    }

    private void initializeS3Client() {
        String endpointUrl = "https://" + doConfig.getRegion() + "." + doConfig.getEndpoint();
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(doConfig.getAccessKey(), doConfig.getSecretKey())
                ))
                .region(Region.of(doConfig.getRegion()))
                .endpointOverride(URI.create(endpointUrl))
                .build();
    }

    private void startCleanupTask() {
        cleanupSubscription = getTicker()
                .onItem().invoke(this::performOrphanCleanup)
                .onFailure().invoke(error -> LOGGER.error("Orphan cleanup timer error", error))
                .subscribe().with(
                        item -> {},
                        failure -> LOGGER.error("Orphan cleanup subscription failed", failure)
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
        if (s3Client != null) {
            s3Client.close();
        }
    }

    private void performOrphanCleanup(Long tick) {
        LOGGER.info("Starting orphan file cleanup in Spaces (tick: {})", tick);

        long startTime = System.currentTimeMillis();
        long filesDeleted = 0;
        long bytesFreed = 0;
        long filesScanned = 0;
        this.lastError = null;

        try {
            Set<String> dbFileKeys = getDatabaseFileKeys()
                    .await().atMost(Duration.ofMinutes(10));

            LOGGER.info("Found {} file keys in database", dbFileKeys.size());

            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(doConfig.getBucketName())
                        .maxKeys(BATCH_SIZE);

                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }

                ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
                List<S3Object> objects = response.contents();

                filesScanned += objects.size();
                LOGGER.debug("Scanned {} objects in current batch", objects.size());

                for (S3Object s3Object : objects) {
                    String fileKey = s3Object.key();

                    if (!dbFileKeys.contains(fileKey)) {
                        try {
                            long fileSize = s3Object.size();
                            if (deleteOrphanFile(fileKey)){
                                filesDeleted++;
                                bytesFreed += fileSize;
                            }
                        } catch (Exception e) {
                            LOGGER.error("Failed to delete orphan file: {}", fileKey, e);
                        }
                    }
                }

                continuationToken = response.nextContinuationToken();

            } while (continuationToken != null);

        } catch (Exception e) {
            LOGGER.error("Error during orphan file cleanup", e);
            this.lastError = e.getMessage();
        }

        orphanFilesDeleted.addAndGet(filesDeleted);
        spaceFreedBytes.addAndGet(bytesFreed);
        totalFilesScanned.addAndGet(filesScanned);
        this.lastCleanupTime = LocalDateTime.now();

        long duration = System.currentTimeMillis() - startTime;
        double bytesFreedMB = (double) bytesFreed / (1024 * 1024);

        SpacesOrphanCleanupStats stats = SpacesOrphanCleanupStats.builder()
                .currentSession(filesDeleted, bytesFreed, filesScanned, duration)
                .cumulativeStats(orphanFilesDeleted.get(), spaceFreedBytes.get(), totalFilesScanned.get())
                .lastCleanupTime(this.lastCleanupTime)
                .nextScheduledCleanup(this.lastCleanupTime.plusSeconds(INTERVAL_SECONDS))
                .cleanupInProgress(false)
                .lastError(this.lastError)
                .build();

        eventBus.publish(ADDRESS_ORPHAN_CLEANUP_STATS, stats);
        LOGGER.info("Orphan cleanup completed in {}ms. Scanned: {} files, Deleted: {} orphans, Freed: {} MB",
                duration, filesScanned, filesDeleted, bytesFreedMB);
    }

    public Uni<SpacesOrphanCleanupStats> getStats() {
        return Uni.combine().all().unis(
                        getDatabaseFileKeys().onItem().transform(Set::size),
                        getSpacesFileCount()
                ).asTuple()
                .onItem().transform(tuple -> {
                    long dbCount = tuple.getItem1();
                    long spacesCount = tuple.getItem2();

                    return SpacesOrphanCleanupStats.builder()
                            .cumulativeStats(orphanFilesDeleted.get(), spaceFreedBytes.get(), totalFilesScanned.get())
                            .lastCleanupTime(this.lastCleanupTime)
                            .nextScheduledCleanup(this.lastCleanupTime != null ?
                                    this.lastCleanupTime.plusSeconds(INTERVAL_SECONDS) : null)
                            .cleanupInProgress(false)
                            .lastError(this.lastError)
                            .fileCounts(dbCount, spacesCount)
                            .build();
                });
    }

    private Uni<Set<String>> getDatabaseFileKeys() {
        String sql = "SELECT DISTINCT file_key FROM _files WHERE file_key IS NOT NULL AND file_key != '' AND archived = 0";

        return pgPool.query(sql)
                .execute()
                .onItem().transform(rows -> {
                    Set<String> fileKeys = new HashSet<>();
                    rows.forEach(row -> {
                        String fileKey = row.getString("file_key");
                        if (fileKey != null && !fileKey.trim().isEmpty()) {
                            fileKeys.add(fileKey);
                        }
                    });
                    return fileKeys;
                })
                .onFailure().invoke(throwable ->
                        LOGGER.error("Failed to retrieve file keys from database", throwable));
    }


    private boolean deleteOrphanFile(String fileKey) {
        try {
            if (doConfig.isDeleteDisabled()) {
                LOGGER.info("Deletion disabled by configuration. Would delete orphan file: {}", fileKey);
                return false;
            }

            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(doConfig.getBucketName())
                    .key(fileKey)
                    .build();

            s3Client.deleteObject(deleteRequest);
            LOGGER.debug("Successfully deleted orphan file from Spaces: {}", fileKey);
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to delete orphan file from Spaces: {}", fileKey, e);
            throw e;
        }
    }

    public Uni<Long> getSpacesFileCount() {
        return Uni.createFrom().item(() -> {
            long totalCount = 0;
            String continuationToken = null;

            do {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(doConfig.getBucketName())
                        .maxKeys(BATCH_SIZE);

                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }

                ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
                totalCount += response.contents().size();
                continuationToken = response.nextContinuationToken();

            } while (continuationToken != null);

            return totalCount;
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }
}