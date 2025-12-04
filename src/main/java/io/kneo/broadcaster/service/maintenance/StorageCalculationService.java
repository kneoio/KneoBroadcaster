package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.config.HetznerConfig;
import io.kneo.broadcaster.repository.StorageUsageRepository;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StorageCalculationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageCalculationService.class);
    private static final int INTERVAL_HOURS = 24;
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(30);

    private final HetznerConfig hetznerConfig;
    private final StorageUsageRepository storageUsageRepository;
    private final PgPool client;
    private Cancellable calculationSubscription;
    private S3Client s3Client;

    @Inject
    public StorageCalculationService(HetznerConfig hetznerConfig, StorageUsageRepository storageUsageRepository, PgPool client) {
        this.hetznerConfig = hetznerConfig;
        this.storageUsageRepository = storageUsageRepository;
        this.client = client;
    }

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Starting storage calculation service");
        initS3Client();
        startCalculationTask();
    }

    private void initS3Client() {
        String endpointUrl = "https://" + hetznerConfig.getEndpoint();
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(hetznerConfig.getAccessKey(), hetznerConfig.getSecretKey())
                ))
                .region(Region.EU_CENTRAL_1)
                .endpointOverride(URI.create(endpointUrl))
                .forcePathStyle(true)
                .build();
    }

    private void startCalculationTask() {
        calculationSubscription = Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(Duration.ofHours(INTERVAL_HOURS))
                .onOverflow().drop()
                .onItem().call(this::calculateStorage)
                .onFailure().invoke(error -> LOGGER.error("Storage calculation error", error))
                .subscribe().with(item -> {}, failure -> LOGGER.error("Subscription failed", failure));
    }

    public void stopCalculationTask() {
        if (calculationSubscription != null) {
            calculationSubscription.cancel();
        }
    }

    private Uni<Void> calculateStorage(Long tick) {
        LOGGER.info("Starting storage calculation");
        LocalDateTime calculationDate = LocalDateTime.now();

        return Uni.createFrom().item(() -> {
                    Map<String, StorageData> storageMap = new ConcurrentHashMap<>();
                    String continuationToken = null;

                    do {
                        ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                                .bucket(hetznerConfig.getBucketName())
                                .maxKeys(1000);

                        if (continuationToken != null) {
                            requestBuilder.continuationToken(continuationToken);
                        }

                        ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());

                        for (S3Object s3Object : response.contents()) {
                            String key = s3Object.key();
                            long size = s3Object.size();
                            processFileKey(key, size, storageMap);
                        }

                        continuationToken = response.nextContinuationToken();
                    } while (continuationToken != null);

                    LOGGER.info("Processed {} storage entries from S3", storageMap.size());
                    return storageMap;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transformToUni(storageMap -> enrichWithAuthorData(storageMap, calculationDate))
                .onItem().transformToUni(records -> storageUsageRepository.insertBatch(records))
                .onItem().invoke(() -> LOGGER.info("Storage calculation completed"))
                .onFailure().invoke(t -> LOGGER.error("Failed to calculate storage", t));
    }

    private void processFileKey(String key, long size, Map<String, StorageData> storageMap) {
        String[] parts = key.split("/");
        if (parts.length < 2) {
            return;
        }

        String stationSlug = parts[0];
        storageMap.computeIfAbsent(stationSlug, k -> new StorageData())
                .addFile(size);
    }

    private Uni<List<StorageUsageRepository.StorageUsageRecord>> enrichWithAuthorData(
            Map<String, StorageData> storageMap, LocalDateTime calculationDate) {

        String sql = "SELECT id, slug_name, author FROM kneobroadcaster__brands WHERE slug_name = ANY($1)";
        String[] slugs = storageMap.keySet().toArray(new String[0]);

        return client.preparedQuery(sql)
                .execute(Tuple.of(slugs))
                .onItem().transform(rows -> {
                    List<StorageUsageRepository.StorageUsageRecord> records = new ArrayList<>();

                    for (Row row : rows) {
                        String slugName = row.getString("slug_name");
                        UUID stationId = row.getUUID("id");
                        Long author = row.getLong("author");
                        StorageData data = storageMap.get(slugName);

                        if (data != null) {
                            records.add(new StorageUsageRepository.StorageUsageRecord(
                                    author,
                                    stationId,
                                    slugName,
                                    data.totalBytes,
                                    data.fileCount,
                                    calculationDate,
                                    "HETZNER"
                            ));
                        }
                    }

                    LOGGER.info("Created {} storage usage records", records.size());
                    return records;
                });
    }

    private static class StorageData {
        long totalBytes = 0;
        int fileCount = 0;

        void addFile(long size) {
            totalBytes += size;
            fileCount++;
        }
    }
}
