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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FileUploadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileUploadService.class);
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192; // 8KB buffer for file copy

    private final String uploadDir;
    private final AudioMetadataService audioMetadataService;
    public final ConcurrentHashMap<String, UploadFileDTO> uploadProgressMap = new ConcurrentHashMap<>();

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

    public UploadFileDTO createUploadSession(String uploadId, String clientStartTimeStr) {
        long clientStartTime = Long.parseLong(clientStartTimeStr);
        long serverReceiveTime = System.currentTimeMillis();
        long estimatedSeconds = (serverReceiveTime - clientStartTime) / 1000;

        UploadFileDTO uploadDto = UploadFileDTO.builder()
                .status("uploading")
                .percentage(0)
                .batchId(uploadId)
                .estimatedDurationSeconds(estimatedSeconds)
                .build();

        uploadProgressMap.put(uploadId, uploadDto);
        return uploadDto;
    }

    public Uni<Void> processFile(FileUpload uploadedFile, String uploadId, String entityId,
                                 IUser user, String originalFileName) {
        return Uni.createFrom().item(() -> {
            try {
                // Step 1: Setup and validation
                updateProgress(uploadId, 10, "validation", null, null, null);
                String safeFileName = sanitizeAndValidateFilename(originalFileName, user);
                Path destination = setupDirectoriesAndPath(entityId, user, safeFileName);

                // Step 2: Move file (atomic operation)
                updateProgress(uploadId, 20, "preparation", null, null, null);
                Path tempFile = Paths.get(uploadedFile.uploadedFileName());
                //Files.move(tempFile, destination);
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);

                // Step 3: Extract metadata (the real work)
                updateProgress(uploadId, 30, "extract_metadata", null, null, null);
                AudioMetadataDTO metadata = extractMetadata(destination, originalFileName, uploadId);

                // Step 4: Complete
                String fileUrl = generateFileUrl(entityId, safeFileName);
                updateProgress(uploadId, 100, "finished", fileUrl, destination.toString(), metadata);

                return (Void) null;
            } catch (Exception e) {
                updateProgress(uploadId, null, "error", null, null, null);
                throw new RuntimeException(e);
            }
        }).emitOn(Infrastructure.getDefaultExecutor()).replaceWithVoid();
    }

    private AudioMetadataDTO extractMetadata(Path destination, String originalFileName, String uploadId) {
        if (!isValidAudioFile(originalFileName, null)) {
            updateProgress(uploadId, 90, "extract_metadata", null, null, null);
            return null;
        }

        try {
            updateProgress(uploadId, 75, "extract_metadata", null, null, null);
            AudioMetadataDTO metadata = audioMetadataService.extractMetadataWithProgress(
                    destination.toString(),
                    (percentage) -> {
                        int overallProgress = 75 + (percentage * 15 / 100);
                        updateProgress(uploadId, Math.min(overallProgress, 90), "extract_metadata", null, null, null);
                    }
            );

            LOGGER.info("Metadata extracted successfully");
            return metadata;
        } catch (Exception e) {
            LOGGER.warn("Metadata extraction failed: {}", e.getMessage());
            updateProgress(uploadId, 90, "error", null, null, null);
            return null;
        }
    }

    public UploadFileDTO getUploadProgress(String uploadId) {
        return uploadProgressMap.get(uploadId);
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
                    .percentage(percentage != null ? percentage : dto.getPercentage())
                    .url(url != null ? url : dto.getUrl())
                    .batchId(dto.getBatchId())
                    .type(dto.getType())
                    .fullPath(fullPath != null ? fullPath : dto.getFullPath())
                    .thumbnailUrl(dto.getThumbnailUrl())
                    .metadata(metadata != null ? metadata : dto.getMetadata())
                    .fileSize(dto.getFileSize())
                    .build();

            uploadProgressMap.put(uploadId, updatedDto);

            LOGGER.debug("Progress update - uploadId: {}, status: {}, percentage: {}%",
                    uploadId, status, percentage);
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