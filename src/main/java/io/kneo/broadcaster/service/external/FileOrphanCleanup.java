package io.kneo.broadcaster.service.external;

import io.kneo.broadcaster.config.HetznerConfig;
import io.kneo.broadcaster.dto.AudioMetadataDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.dashboard.SpacesOrphanCleanupStatsDTO;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.AudioMetadataService;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class FileOrphanCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileOrphanCleanup.class);
    private static final int INTERVAL_SECONDS = 6000;
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(10);
    private static final String ADDRESS_ORPHAN_CLEANUP_STATS = "spaces-orphan-cleanup-stats";
    private LocalDateTime lastCleanupTime;
    private String lastError;
    private static final int BATCH_SIZE = 1000;

    private final HetznerConfig hetznerConfig;
    private final PgPool pgPool;
    private final EventBus eventBus;
    private final AudioMetadataService audioMetadataService;
    private final SoundFragmentService soundFragmentService;
    private S3Client s3Client;
    private Cancellable cleanupSubscription;

    @Getter
    private final AtomicLong orphanFilesDeleted = new AtomicLong(0);
    @Getter
    private final AtomicLong orphanFilesSaved = new AtomicLong(0);
    @Getter
    private final AtomicLong spaceFreedBytes = new AtomicLong(0);
    @Getter
    private final AtomicLong totalFilesScanned = new AtomicLong(0);

    @Inject
    public FileOrphanCleanup(HetznerConfig hetznerConfig, PgPool pgPool, EventBus eventBus,
                             AudioMetadataService audioMetadataService,
                             SoundFragmentService soundFragmentService) {
        this.hetznerConfig = hetznerConfig;
        this.pgPool = pgPool;
        this.eventBus = eventBus;
        this.audioMetadataService = audioMetadataService;
        this.soundFragmentService = soundFragmentService;
    }

    void onStart(@Observes StartupEvent event) {
        initializeS3Client();
        LOGGER.info("Starting Spaces file orphan cleanup service.");
        startCleanupTask();
    }

    private void initializeS3Client() {
        String endpointUrl = "https://" + hetznerConfig.getEndpoint();
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(hetznerConfig.getAccessKey(), hetznerConfig.getSecretKey())
                ))
                .region(Region.of(hetznerConfig.getRegion()))
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
        long filesSaved = 0;
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
                        .bucket(hetznerConfig.getBucketName())
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
                            OrphanProcessingResult result = processOrphanFile(fileKey);

                            switch (result) {
                                case DELETED:
                                    filesDeleted++;
                                    bytesFreed += fileSize;
                                    break;
                                case SAVED:
                                    filesSaved++;
                                    break;
                                case SKIPPED:
                                    break;
                            }
                        } catch (Exception e) {
                            LOGGER.error("Failed to process orphan file: {}", fileKey, e);
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
        orphanFilesSaved.addAndGet(filesSaved);
        spaceFreedBytes.addAndGet(bytesFreed);
        totalFilesScanned.addAndGet(filesScanned);
        this.lastCleanupTime = LocalDateTime.now();

        long duration = System.currentTimeMillis() - startTime;
        double bytesFreedMB = (double) bytesFreed / (1024 * 1024);

        SpacesOrphanCleanupStatsDTO stats = SpacesOrphanCleanupStatsDTO.builder()
                .currentSession(filesDeleted, bytesFreed, filesScanned, duration)
                .cumulativeStats(orphanFilesDeleted.get(), spaceFreedBytes.get(), totalFilesScanned.get())
                .lastCleanupTime(this.lastCleanupTime)
                .nextScheduledCleanup(this.lastCleanupTime.plusSeconds(INTERVAL_SECONDS))
                .cleanupInProgress(false)
                .lastError(this.lastError)
                .build();

        eventBus.publish(ADDRESS_ORPHAN_CLEANUP_STATS, stats);
        LOGGER.info("Orphan cleanup completed in {}ms. Scanned: {} files, Deleted: {} orphans, Saved: {} recoverable, Freed: {} MB",
                duration, filesScanned, filesDeleted, filesSaved, bytesFreedMB);
    }

    private OrphanProcessingResult processOrphanFile(String fileKey) {
        if (!isAudioFile(fileKey)) {
            LOGGER.warn("Orphan file is not an audio file, deleting: {}", fileKey);
            if (deleteOrphanFile(fileKey)) {
                return OrphanProcessingResult.DELETED;
            }
            return OrphanProcessingResult.SKIPPED;
        }

        Path tempFile = null;
        try {
            tempFile = downloadFileTemporarily(fileKey);
            if (tempFile == null) {
                LOGGER.warn("Failed to download orphan file for metadata analysis: {}", fileKey);
                return OrphanProcessingResult.SKIPPED;
            }

            AudioMetadataDTO metadata = audioMetadataService.extractMetadata(tempFile.toString());

            if (hasCompleteMetadata(metadata)) {
                String genre = metadata.getGenre() != null && !metadata.getGenre().trim().isEmpty()
                        ? metadata.getGenre() : "Other";

                LOGGER.info("Found orphan file with complete metadata - Title: {}, Artist: {}, Genre: {}, File: {}",
                        metadata.getTitle(), metadata.getArtist(), genre, fileKey);

                if (saveOrphanToDatabase(metadata, fileKey)) {
                    LOGGER.info("Successfully saved orphan file to database: {}", fileKey);
                    return OrphanProcessingResult.SAVED;
                } else {
                    LOGGER.warn("Failed to save orphan file to database, deleting: {}", fileKey);
                    if (deleteOrphanFile(fileKey)) {
                        return OrphanProcessingResult.DELETED;
                    }
                    return OrphanProcessingResult.SKIPPED;
                }
            } else {
                FileKeyMetadata keyMetadata = extractMetadataFromFileKey(fileKey);
                if (keyMetadata != null) {
                    metadata.setTitle(keyMetadata.getTitle());
                    metadata.setArtist(keyMetadata.getArtist());
                    metadata.setGenre(keyMetadata.getGenre());

                    LOGGER.info("Extracted metadata from file key - Title: {}, Artist: {}, Genre: {}, File: {}",
                            metadata.getTitle(), metadata.getArtist(), metadata.getGenre(), fileKey);

                    if (saveOrphanToDatabase(metadata, fileKey)) {
                        LOGGER.info("Successfully saved orphan file using key metadata to database: {}", fileKey);
                        return OrphanProcessingResult.SAVED;
                    } else {
                        LOGGER.warn("Failed to save orphan file with key metadata to database, deleting: {}", fileKey);
                        if (deleteOrphanFile(fileKey)) {
                            return OrphanProcessingResult.DELETED;
                        }
                        return OrphanProcessingResult.SKIPPED;
                    }
                } else {
                    LOGGER.warn("Orphan file lacks required metadata and invalid key structure (Title: {}, Artist: {}), deleting: {}",
                            metadata.getTitle(), metadata.getArtist(), fileKey);
                    if (deleteOrphanFile(fileKey)) {
                        return OrphanProcessingResult.DELETED;
                    }
                    return OrphanProcessingResult.SKIPPED;
                }
            }

        } catch (Exception e) {
            LOGGER.error("Error processing orphan file: {}", fileKey, e);
            return OrphanProcessingResult.SKIPPED;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete temporary file: {}", tempFile, e);
                }
            }
        }
    }

    private FileKeyMetadata extractMetadataFromFileKey(String fileKey) {
        try {
            String[] parts = fileKey.split("/");

            if (parts.length != 4) {
                LOGGER.debug("File key doesn't match expected structure (4 parts): {}", fileKey);
                return null;
            }

            if (!"soundfragments".equals(parts[0])) {
                LOGGER.debug("File key doesn't start with 'soundfragments': {}", fileKey);
                return null;
            }

            String genre = parts[1];
            String artist = parts[2];
            String filename = parts[3];

            if (genre.trim().isEmpty() || artist.trim().isEmpty() || filename.trim().isEmpty()) {
                LOGGER.debug("File key has empty parts - Genre: '{}', Artist: '{}', Filename: '{}'",
                        genre, artist, filename);
                return null;
            }

            String title = filename;
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex > 0) {
                title = filename.substring(0, dotIndex);
            }

            try {
                UUID.fromString(title);
                title = artist + " - Track " + title.substring(0, 8);
            } catch (IllegalArgumentException e) {
            }

            return FileKeyMetadata.builder()
                    .title(title)
                    .artist(artist)
                    .genre(genre)
                    .build();

        } catch (Exception e) {
            LOGGER.debug("Failed to extract metadata from file key: {}", fileKey, e);
            return null;
        }
    }

    private boolean isAudioFile(String fileKey) {
        String lowerKey = fileKey.toLowerCase();
        try {
            UUID.fromString(fileKey);
            return true;
        } catch (IllegalArgumentException e) {
            return lowerKey.endsWith(".mp3") || lowerKey.endsWith(".wav") ||
                    lowerKey.endsWith(".flac") || lowerKey.endsWith(".m4a") ||
                    lowerKey.endsWith(".aac") || lowerKey.endsWith(".ogg") ||
                    lowerKey.endsWith(".wma") || lowerKey.endsWith(".ape");
        }
    }

    private Path downloadFileTemporarily(String fileKey) {
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "orphan-cleanup");
            Files.createDirectories(tempDir);

            String fileName = Paths.get(fileKey).getFileName().toString();
            Path tempFile = tempDir.resolve(fileName);

            if (Files.exists(tempFile)) {
                return tempFile;
            }

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(hetznerConfig.getBucketName())
                    .key(fileKey)
                    .build();

            s3Client.getObject(getObjectRequest, tempFile);
            return tempFile;

        } catch (Exception e) {
            LOGGER.error("Failed to download file temporarily: {}", fileKey, e);
            return null;
        }
    }

    private boolean hasCompleteMetadata(AudioMetadataDTO metadata) {
        return metadata.getTitle() != null && !metadata.getTitle().trim().isEmpty() &&
                metadata.getArtist() != null && !metadata.getArtist().trim().isEmpty();
    }

    private boolean saveOrphanToDatabase(AudioMetadataDTO metadata, String fileKey) {
        try {
            String genre = metadata.getGenre();
            if (genre == null || genre.trim().isEmpty()) {
                genre = "Other";
            }

            SoundFragmentDTO dto = SoundFragmentDTO.builder()
                    .title(metadata.getTitle())
                    .artist(metadata.getArtist())
                    //.genres(List.of(genre))
                    .album(metadata.getAlbum())
                    .source(SourceType.RECOVERED_FROM_STORAGE)
                    .status(1)
                    .type(PlaylistItemType.SONG)
                    .newlyUploaded(List.of())
                    .representedInBrands(List.of())
                    .build();

            SuperUser superUser = SuperUser.build();

            SoundFragmentDTO savedDto = soundFragmentService.upsert(null, dto, superUser, LanguageCode.en)
                    .await().atMost(Duration.ofMinutes(1));

            boolean fileCreated = createFileMetadataRecord(savedDto.getId(), fileKey, metadata);

            if (!fileCreated) {
                LOGGER.error("Failed to create file metadata record for orphan: {}", fileKey);
                return false;
            }

            LOGGER.info("Successfully created database entry for orphan file: {}", fileKey);
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to save orphan to database: {}", fileKey, e);
            return false;
        }
    }

    private boolean createFileMetadataRecord(UUID soundFragmentId, String fileKey, AudioMetadataDTO metadata) {
        try {
            String sql = """
            INSERT INTO _files (
                parent_table, parent_id, storage_type, file_key, file_original_name,
                slug_name, mime_type, reg_date, last_mod_date, archived
            ) VALUES (
                'kneobroadcaster__sound_fragments', $1, 'HETZNER', $2, $3,
                $4, 'audio/mpeg', NOW(), NOW(), 0
            )
            """;

            String originalName = Paths.get(fileKey).getFileName().toString();
            String slugName = generateSlugName(metadata.getTitle(), metadata.getArtist());

            pgPool.preparedQuery(sql)
                    .execute(io.vertx.mutiny.sqlclient.Tuple.of(soundFragmentId, fileKey, originalName, slugName))
                    .await().atMost(Duration.ofSeconds(30));

            LOGGER.info("Created file metadata record for orphan: {} -> {}", fileKey, soundFragmentId);
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to create file metadata record: {}", fileKey, e);
            return false;
        }
    }

    private String generateSlugName(String title, String artist) {
        if (title == null || artist == null) return "orphan-file";
        return (artist + "-" + title).toLowerCase()
                .replaceAll("[^a-z0-9\\-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    public Uni<SpacesOrphanCleanupStatsDTO> getStats() {
        return Uni.combine().all().unis(
                        getDatabaseFileKeys().onItem().transform(Set::size),
                        getSpacesFileCount()
                ).asTuple()
                .onItem().transform(tuple -> {
                    long dbCount = tuple.getItem1();
                    long spacesCount = tuple.getItem2();

                    return SpacesOrphanCleanupStatsDTO.builder()
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
            if (hetznerConfig.isDeleteDisabled()) {
                LOGGER.info("Deletion disabled by configuration. Would delete orphan file: {}", fileKey);
                return false;
            }

            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(hetznerConfig.getBucketName())
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
                        .bucket(hetznerConfig.getBucketName())
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

    @Builder
    @Getter
    private static class FileKeyMetadata {
        private final String title;
        private final String artist;
        private final String genre;
    }

    private enum OrphanProcessingResult {
        DELETED,
        SAVED,
        SKIPPED
    }
}