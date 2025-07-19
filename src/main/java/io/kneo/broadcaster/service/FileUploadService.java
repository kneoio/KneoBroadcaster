package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.AudioMetadataDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.service.manipulation.AudioMetadataService;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.web.FileUpload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FileUploadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileUploadService.class);
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;

    private final String uploadDir;
    private final AudioMetadataService audioMetadataService;
    private final ConcurrentHashMap<String, UploadFileDTO> uploadProgressMap = new ConcurrentHashMap<>();

    private static final Set<String> SUPPORTED_AUDIO_EXTENSIONS = Set.of(
            "mp3", "wav", "flac", "aac", "ogg", "m4a"
    );

    private static final Set<String> SUPPORTED_AUDIO_MIME_TYPES = Set.of(
            "audio/mpeg", "audio/wav", "audio/wave", "audio/x-wav",
            "audio/flac", "audio/x-flac", "audio/aac", "audio/ogg",
            "audio/mp4", "audio/x-m4a"
    );

    @Inject
    public FileUploadService(BroadcasterConfig config, AudioMetadataService audioMetadataService) {
        this.uploadDir = config.getPathUploads() + "/sound-fragments-controller";
        this.audioMetadataService = audioMetadataService;
    }

    public void validateUpload(FileUpload uploadedFile) {
        if (uploadedFile.size() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(String.format("File too large. Maximum size is %d MB for audio files",
                    MAX_FILE_SIZE_BYTES / 1024 / 1024));
        }

        String originalFileName = uploadedFile.fileName();
        if (!isValidAudioFile(originalFileName, uploadedFile.contentType())) {
            throw new IllegalArgumentException("Unsupported file type. Only audio files are allowed: " +
                    String.join(", ", SUPPORTED_AUDIO_EXTENSIONS));
        }
    }

    public UploadFileDTO createUploadSession(String uploadId, String entityId, FileUpload uploadedFile) {
        UploadFileDTO uploadDto = UploadFileDTO.builder()
                .id(uploadId)
                .name(uploadedFile.fileName())
                .status("uploading")
                .percentage(0)
                .type(uploadedFile.contentType())
                .batchId(entityId)
                .fileSize(uploadedFile.size())
                .build();

        uploadProgressMap.put(uploadId, uploadDto);
        return uploadDto;
    }

    public Uni<Void> processFile(FileUpload uploadedFile, String uploadId, String entityId,
                                 IUser user, String originalFileName) {
        return Uni.createFrom().item(() -> {
            try {
                long totalFileSize = uploadedFile.size();

                // Phase 1: Validation (0-5%)
                updateProgress(uploadId, 0, "validating", null, null, null);
                String safeFileName = sanitizeAndValidateFilename(originalFileName, user);
                updateProgress(uploadId, 5, "validating", null, null, null);

                // Phase 2: Setup directories (5-10%)
                Path destination = setupDirectoriesAndPath(entityId, user, safeFileName);
                updateProgress(uploadId, 10, "uploading", null, null, null);

                // Phase 3: File copy with real progress (10-60%)
                copyFileWithProgress(uploadedFile, destination, uploadId, totalFileSize);

                // Phase 4: Metadata extraction (60-90%)
                updateProgress(uploadId, 60, "processing", null, null, null);
                AudioMetadataDTO metadata = extractMetadataWithRealProgress(destination, originalFileName, uploadId);

                // Phase 5: Finalization (90-100%)
                updateProgress(uploadId, 90, "finalizing", null, null, null);
                String fileUrl = generateFileUrl(entityId, safeFileName);

                // Complete
                updateProgress(uploadId, 100, "finished", fileUrl, destination.toString(), metadata);

                return (Void) null;
            } catch (Exception e) {
                updateProgress(uploadId, 0, "error", null, null, null);
                LOGGER.error("File upload failed for uploadId: {}", uploadId, e);
                throw new RuntimeException(e);
            }
        }).emitOn(Infrastructure.getDefaultExecutor()).replaceWithVoid();
    }

    private void copyFileWithProgress(FileUpload uploadedFile, Path destination, String uploadId, long totalSize) throws IOException {
        Path tempFile = Paths.get(uploadedFile.uploadedFileName());

        try (InputStream in = new BufferedInputStream(new FileInputStream(tempFile.toFile()), BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination.toFile()), BUFFER_SIZE)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            long totalBytesRead = 0;
            int bytesRead;
            int lastReportedProgress = 10;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                // Calculate progress (10-60% range for file copy)
                int currentProgress = 10 + (int)((totalBytesRead * 50) / totalSize);

                // Only update if progress changed by at least 2%
                if (currentProgress - lastReportedProgress >= 2) {
                    updateProgress(uploadId, currentProgress, "uploading", null, null, null);
                    lastReportedProgress = currentProgress;
                }
            }

            out.flush();
        }

        // Verify file was copied correctly
        if (!Files.exists(destination)) {
            throw new IOException("File copy verification failed - destination doesn't exist");
        }

        long copiedSize = Files.size(destination);
        if (copiedSize != totalSize) {
            throw new IOException("File size verification failed - expected " + totalSize + " but got " + copiedSize);
        }

        // Clean up temp file
        Files.deleteIfExists(tempFile);

        LOGGER.info("Successfully copied file {} ({} MB) to {}",
                uploadedFile.fileName(), totalSize / 1024 / 1024, destination);
    }

    private AudioMetadataDTO extractMetadataWithRealProgress(Path destination, String originalFileName, String uploadId) {
        AudioMetadataDTO metadata = null;

        if (isValidAudioFile(originalFileName, null)) {
            try {
                LOGGER.info("Starting metadata extraction for audio file: {}", originalFileName);

                // Use the metadata service with progress callback
                metadata = audioMetadataService.extractMetadataWithProgress(
                        destination.toString(),
                        (percentage) -> {
                            // Map metadata extraction progress (0-100%) to overall progress (60-90%)
                            int overallProgress = 60 + (percentage * 30 / 100);
                            updateProgress(uploadId, overallProgress, "processing", null, null, null);
                        }
                );

                LOGGER.info("Successfully extracted metadata - Title: {}, Artist: {}, Duration: {}s",
                        metadata.getTitle(), metadata.getArtist(), metadata.getDurationSeconds());
            } catch (Exception e) {
                LOGGER.warn("Could not extract metadata from audio file: {}", originalFileName, e);
                // Continue without metadata
                updateProgress(uploadId, 90, "processing", null, null, null);
            }
        } else {
            // Not an audio file, skip to 90%
            updateProgress(uploadId, 90, "processing", null, null, null);
        }

        return metadata;
    }

    public UploadFileDTO getUploadProgress(String uploadId) {
        return uploadProgressMap.get(uploadId);
    }

    public void cleanupUploadSession(String uploadId) {
        uploadProgressMap.remove(uploadId);
    }

    private String sanitizeAndValidateFilename(String originalFileName, IUser user) {
        try {
            return FileSecurityUtils.sanitizeFilename(originalFileName);
        } catch (SecurityException e) {
            LOGGER.warn("Unsafe filename rejected: {} from user: {}", originalFileName, user.getUserName());
            throw new IllegalArgumentException("Invalid filename: " + e.getMessage());
        }
    }

    private Path setupDirectoriesAndPath(String entityId, IUser user, String safeFileName) throws Exception {
        Path userDir = Files.createDirectories(Paths.get(uploadDir, user.getUserName()));
        String entityIdSafe = entityId != null ? entityId : "temp";

        if (!"temp".equals(entityIdSafe)) {
            try {
                UUID.fromString(entityIdSafe);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid entity ID: {} from user: {}", entityIdSafe, user.getUserName());
                throw new IllegalArgumentException("Invalid entity ID");
            }
        }

        Path entityDir = Files.createDirectories(userDir.resolve(entityIdSafe));
        Path destination = FileSecurityUtils.secureResolve(entityDir, safeFileName);
        Path expectedBase = Paths.get(uploadDir, user.getUserName(), entityIdSafe);

        if (!FileSecurityUtils.isPathWithinBase(expectedBase, destination)) {
            LOGGER.error("Security violation: Path traversal attempt by user {} with filename {}",
                    user.getUserName(), safeFileName);
            throw new SecurityException("Invalid file path");
        }

        return destination;
    }

    private String generateFileUrl(String entityId, String safeFileName) {
        String entityIdSafe = entityId != null ? entityId : "temp";
        return String.format("/api/soundfragments/files/%s/%s", entityIdSafe, safeFileName);
    }

    private void updateProgress(String uploadId, Integer percentage, String status, String url, String fullPath, AudioMetadataDTO metadata) {
        UploadFileDTO dto = uploadProgressMap.get(uploadId);
        if (dto != null) {
            UploadFileDTO updatedDto = UploadFileDTO.builder()
                    .id(dto.getId())
                    .name(dto.getName())
                    .status(status)
                    .percentage(percentage)
                    .url(url)
                    .batchId(dto.getBatchId())
                    .type(dto.getType())
                    .fullPath(fullPath)
                    .thumbnailUrl(dto.getThumbnailUrl())
                    .metadata(metadata)
                    .fileSize(dto.getFileSize())
                    .build();

            uploadProgressMap.put(uploadId, updatedDto);
        }
    }

    private boolean isValidAudioFile(String filename, String contentType) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }

        String extension = getFileExtension(filename.toLowerCase());
        boolean validExtension = SUPPORTED_AUDIO_EXTENSIONS.contains(extension);

        boolean validMimeType = contentType != null &&
                SUPPORTED_AUDIO_MIME_TYPES.stream().anyMatch(contentType::startsWith);

        return validExtension || validMimeType;
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1);
        }
        return "";
    }
}