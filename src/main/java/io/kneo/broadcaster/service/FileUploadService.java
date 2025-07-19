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
                .build();

        uploadProgressMap.put(uploadId, uploadDto);
        return uploadDto;
    }

    public Uni<Void> processFile(FileUpload uploadedFile, String uploadId, String entityId,
                                 IUser user, String originalFileName) {
        return Uni.createFrom().item(() -> {
            try {
                updateProgress(uploadId, 0, "uploading", null, null, null);
                Thread.sleep(2000);
                String safeFileName = sanitizeAndValidateFilename(originalFileName, user);
                updateProgress(uploadId, 5, "uploading", null, null, null);
                Thread.sleep(1500);
                Path destination = setupDirectoriesAndPath(entityId, user, safeFileName);
                updateProgress(uploadId, 20, "uploading", null, null, null);
                Thread.sleep(2000);
                moveAndVerifyFile(uploadedFile, destination, originalFileName, user);
                updateProgress(uploadId, 45, "uploading", null, null, null);
                Thread.sleep(1500);
                AudioMetadataDTO metadata = extractMetadata(destination, originalFileName, uploadId);

                String fileUrl = generateFileUrl(entityId, safeFileName);
                finalizeUpload(uploadId, fileUrl, destination, metadata);

                return (Void) null;
            } catch (Exception e) {
                updateProgress(uploadId, 0, "error", null, null, null);
                throw new RuntimeException(e);
            }
        }).emitOn(Infrastructure.getDefaultExecutor()).replaceWithVoid();
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

    private void moveAndVerifyFile(FileUpload uploadedFile, Path destination, String originalFileName, IUser user) throws Exception {
        Path tempFile = Paths.get(uploadedFile.uploadedFileName());
        Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("Moved uploaded file {} ({} MB) to {} for user: {}",
                originalFileName, uploadedFile.size() / 1024 / 1024,
                destination, user.getUserName());

        if (!Files.exists(destination)) {
            throw new RuntimeException("File move verification failed");
        }

        long fileSize = Files.size(destination);
        if (fileSize != uploadedFile.size()) {
            throw new RuntimeException("File size verification failed");
        }
    }

    private AudioMetadataDTO extractMetadata(Path destination, String originalFileName, String uploadId) {
        AudioMetadataDTO metadata = null;
        if (isValidAudioFile(originalFileName, null)) {
            try {
                LOGGER.info("Starting metadata extraction for audio file: {}", originalFileName);
                updateProgress(uploadId, 50, "processing", null, null, null);
                Thread.sleep(2000);
                updateProgress(uploadId, 60, "processing", null, null, null);
                Thread.sleep(2000);
                metadata = audioMetadataService.extractMetadataWithProgress(
                        destination.toString(),
                        (percentage) -> {
                            updateProgress(uploadId, 60 + (percentage * 20 / 100), "processing", null, null, null);
                            try { Thread.sleep(200); } catch (InterruptedException e) {}
                        }
                );
                updateProgress(uploadId, 80, "processing", null, null, null);
                Thread.sleep(1000);

                LOGGER.info("Successfully extracted metadata - Title: {}, Artist: {}, Duration: {}s",
                        metadata.getTitle(), metadata.getArtist(), metadata.getDurationSeconds());
            } catch (Exception e) {
                LOGGER.warn("Could not extract metadata from audio file: {}", originalFileName, e);
                updateProgress(uploadId, 80, "processing", null, null, null);
            }
        } else {
            updateProgress(uploadId, 80, "processing", null, null, null);
        }
        return metadata;
    }

    private void finalizeUpload(String uploadId, String fileUrl, Path destination, AudioMetadataDTO metadata) throws Exception {
        updateProgress(uploadId, 85, "uploading", fileUrl, destination.toString(), metadata);
        Thread.sleep(5000);
        updateProgress(uploadId, 90, "processing", fileUrl, destination.toString(), metadata);
        Thread.sleep(3000);
        updateProgress(uploadId, 95, "finalizing", fileUrl, destination.toString(), metadata);
        Thread.sleep(2000);
        updateProgress(uploadId, 100, "finished", fileUrl, destination.toString(), metadata);
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
                    .build();

            uploadProgressMap.put(uploadId, updatedDto);
        }
    }

    private String getCurrentUploadId() {
        return uploadProgressMap.entrySet().stream()
                .filter(entry -> "uploading".equals(entry.getValue().getStatus()))
                .map(entry -> entry.getKey())
                .findFirst()
                .orElse("");
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