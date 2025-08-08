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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
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

        long timeDiffMs = serverReceiveTime - clientStartTime;
        long estimatedSeconds = Math.max(10, (timeDiffMs / 1000) + 2);

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
                LOGGER.info("FILE_PROCESS_START - UploadId: {}, EntityId: {}, User: {}, OriginalFile: {}, TempFile: {}",
                        uploadId, entityId, user.getUserName(), originalFileName, uploadedFile.uploadedFileName());

                // Step 1: Setup and validation
                updateProgress(uploadId, 10, "validation", null, null, null);
                String safeFileName = sanitizeAndValidateFilename(originalFileName, user);
                Path destination = setupDirectoriesAndPath(entityId, user, safeFileName);

                LOGGER.info("FILE_PROCESS_PATHS - SafeFileName: {}, Destination: {}", safeFileName, destination);

                // Step 2: Pre-move diagnostics
                updateProgress(uploadId, 20, "preparation", null, null, null);
                Path tempFile = Paths.get(uploadedFile.uploadedFileName());

                // NEW: Comprehensive pre-move checks
                performPreMoveChecks(tempFile, destination, uploadId, entityId, user);

                // Step 3: Move file (atomic operation)
                LOGGER.info("FILE_MOVE_START - From: {} -> To: {}", tempFile, destination);
                try {
                    Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("FILE_MOVE_SUCCESS - File moved successfully to: {}", destination);
                } catch (Exception moveException) {
                    LOGGER.error("FILE_MOVE_FAILED - Error moving '{}' to '{}': {}",
                            tempFile, destination, moveException.getMessage(), moveException);

                    // NEW: Additional diagnostics on failure
                    performPostMoveFailureDiagnostics(tempFile, destination, moveException, uploadId);
                    throw moveException;
                }

                // NEW: Post-move verification
                performPostMoveVerification(destination, uploadId);

                // Step 4: Extract metadata (the real work)
                updateProgress(uploadId, 30, "extract_metadata", null, null, null);
                AudioMetadataDTO metadata = extractMetadata(destination, originalFileName, uploadId);

                // Step 5: Complete
                String fileUrl = generateFileUrl(entityId, safeFileName);
                updateProgress(uploadId, 100, "finished", fileUrl, destination.toString(), metadata);

                LOGGER.info("FILE_PROCESS_SUCCESS - UploadId: {}, FinalPath: {}, FileUrl: {}",
                        uploadId, destination, fileUrl);

                return (Void) null;
            } catch (Exception e) {
                LOGGER.error("FILE_PROCESS_ERROR - UploadId: {}, Error: {}", uploadId, e.getMessage(), e);
                updateProgress(uploadId, null, "error", null, null, null);
                throw new RuntimeException(e);
            }
        }).emitOn(Infrastructure.getDefaultExecutor()).replaceWithVoid();
    }

    // NEW: Pre-move diagnostic method
    private void performPreMoveChecks(Path tempFile, Path destination, String uploadId, String entityId, IUser user) {
        LOGGER.info("PRE_MOVE_CHECKS_START - UploadId: {}", uploadId);

        try {
            // Check source file
            if (!Files.exists(tempFile)) {
                LOGGER.error("PRE_MOVE_CHECK_FAILED - Source file does not exist: {}", tempFile);
                throw new IOException("Source file does not exist: " + tempFile);
            }

            BasicFileAttributes tempAttrs = Files.readAttributes(tempFile, BasicFileAttributes.class);
            LOGGER.info("SOURCE_FILE_INFO - Path: {}, Size: {} bytes, Created: {}, Modified: {}, Readable: {}, Writable: {}",
                    tempFile, tempAttrs.size(), tempAttrs.creationTime(), tempAttrs.lastModifiedTime(),
                    Files.isReadable(tempFile), Files.isWritable(tempFile));

            // Check destination
            boolean destExists = Files.exists(destination);
            LOGGER.info("DESTINATION_CHECK - Path: {}, Exists: {}", destination, destExists);

            if (destExists) {
                BasicFileAttributes destAttrs = Files.readAttributes(destination, BasicFileAttributes.class);
                LOGGER.warn("DESTINATION_EXISTS - Path: {}, Size: {} bytes, Created: {}, Modified: {}, Readable: {}, Writable: {}",
                        destination, destAttrs.size(), destAttrs.creationTime(), destAttrs.lastModifiedTime(),
                        Files.isReadable(destination), Files.isWritable(destination));

                // Try to get file permissions (Unix/Linux systems)
                try {
                    String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(destination));
                    LOGGER.info("DESTINATION_PERMISSIONS - Path: {}, Permissions: {}", destination, permissions);
                } catch (Exception e) {
                    LOGGER.debug("Could not read POSIX permissions (probably Windows): {}", e.getMessage());
                }
            }

            // Check parent directory
            Path parentDir = destination.getParent();
            if (parentDir != null) {
                LOGGER.info("PARENT_DIR_CHECK - Path: {}, Exists: {}, Readable: {}, Writable: {}",
                        parentDir, Files.exists(parentDir), Files.isReadable(parentDir), Files.isWritable(parentDir));
            }

            // Check for any file locks or processes using the destination file
            if (destExists) {
                checkFileLocks(destination, uploadId);
            }

        } catch (IOException e) {
            LOGGER.error("PRE_MOVE_CHECKS_ERROR - UploadId: {}, Error: {}", uploadId, e.getMessage(), e);
            throw new RuntimeException("Pre-move checks failed", e);
        }

        LOGGER.info("PRE_MOVE_CHECKS_SUCCESS - UploadId: {}", uploadId);
    }

    private void performPostMoveFailureDiagnostics(Path tempFile, Path destination, Exception moveException, String uploadId) {
        LOGGER.error("POST_MOVE_FAILURE_DIAGNOSTICS_START - UploadId: {}", uploadId);

        try {
            // Check if source still exists
            boolean sourceExists = Files.exists(tempFile);
            LOGGER.error("FAILURE_DIAGNOSTIC - Source file still exists: {} ({})", sourceExists, tempFile);

            // Check if destination was partially created
            boolean destExists = Files.exists(destination);
            LOGGER.error("FAILURE_DIAGNOSTIC - Destination exists after failure: {} ({})", destExists, destination);

            if (destExists) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(destination, BasicFileAttributes.class);
                    LOGGER.error("FAILURE_DIAGNOSTIC - Destination file size: {} bytes, modified: {}",
                            attrs.size(), attrs.lastModifiedTime());
                } catch (IOException e) {
                    LOGGER.error("FAILURE_DIAGNOSTIC - Could not read destination attributes: {}", e.getMessage());
                }
            }

            // Check available disk space
            try {
                long freeSpace = Files.getFileStore(destination.getParent()).getUsableSpace();
                long totalSpace = Files.getFileStore(destination.getParent()).getTotalSpace();
                LOGGER.error("FAILURE_DIAGNOSTIC - Disk space - Free: {} MB, Total: {} MB",
                        freeSpace / 1024 / 1024, totalSpace / 1024 / 1024);
            } catch (IOException e) {
                LOGGER.error("FAILURE_DIAGNOSTIC - Could not check disk space: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.error("POST_MOVE_FAILURE_DIAGNOSTICS_ERROR - Error during diagnostics: {}", e.getMessage());
        }
    }

    private void performPostMoveVerification(Path destination, String uploadId) {
        LOGGER.info("POST_MOVE_VERIFICATION_START - Path: {}, UploadId: {}", destination, uploadId);

        try {
            if (!Files.exists(destination)) {
                LOGGER.error("POST_MOVE_VERIFICATION_FAILED - Destination file does not exist after move: {}", destination);
                throw new IOException("File move appeared to succeed but destination file does not exist");
            }

            BasicFileAttributes attrs = Files.readAttributes(destination, BasicFileAttributes.class);
            LOGGER.info("POST_MOVE_VERIFICATION_SUCCESS - File exists, Size: {} bytes, Modified: {}",
                    attrs.size(), attrs.lastModifiedTime());

        } catch (IOException e) {
            LOGGER.error("POST_MOVE_VERIFICATION_ERROR - Error verifying moved file: {}", e.getMessage(), e);
            throw new RuntimeException("Post-move verification failed", e);
        }
    }

    private AudioMetadataDTO extractMetadata(Path destination, String originalFileName, String uploadId) {
        if (!isValidAudioFile(originalFileName, null)) {
            LOGGER.info("METADATA_SKIP - Not an audio file: {}", originalFileName);
            updateProgress(uploadId, 90, "extract_metadata", null, null, null);
            return null;
        }

        try {
            LOGGER.info("METADATA_EXTRACTION_START - File: {}, UploadId: {}", destination, uploadId);
            updateProgress(uploadId, 75, "extract_metadata", null, null, null);

            AudioMetadataDTO metadata = audioMetadataService.extractMetadataWithProgress(
                    destination.toString(),
                    (percentage) -> {
                        int overallProgress = 75 + (percentage * 15 / 100);
                        updateProgress(uploadId, Math.min(overallProgress, 90), "extract_metadata", null, null, null);
                    }
            );

            LOGGER.info("METADATA_EXTRACTION_SUCCESS - UploadId: {}, Title: {}, Artist: {}, Duration: {}s",
                    uploadId,
                    metadata != null ? metadata.getTitle() : "null",
                    metadata != null ? metadata.getArtist() : "null",
                    metadata != null ? metadata.getDurationSeconds() : "null");
            return metadata;
        } catch (Exception e) {
            LOGGER.warn("METADATA_EXTRACTION_FAILED - UploadId: {}, Error: {}", uploadId, e.getMessage(), e);
            updateProgress(uploadId, 90, "error", null, null, null);
            return null;
        }
    }

    private void checkFileLocks(Path filePath, String uploadId) {
        LOGGER.info("FILE_LOCK_CHECK_START - Path: {}, UploadId: {}", filePath, uploadId);

        try {
            // Try to open the file for writing to check if it's locked
            if (Files.isWritable(filePath)) {
                // On some systems, we can try to rename the file to itself to check locks
                Path tempName = filePath.resolveSibling(filePath.getFileName() + ".lock-test-" + System.currentTimeMillis());
                try {
                    Files.move(filePath, tempName);
                    Files.move(tempName, filePath);
                    LOGGER.info("FILE_LOCK_CHECK_SUCCESS - File is not locked: {}", filePath);
                } catch (IOException e) {
                    LOGGER.warn("FILE_LOCK_CHECK_POSSIBLE_LOCK - File might be locked or in use: {} - {}", filePath, e.getMessage());
                }
            } else {
                LOGGER.warn("FILE_LOCK_CHECK_NOT_WRITABLE - File is not writable: {}", filePath);
            }
        } catch (Exception e) {
            LOGGER.error("FILE_LOCK_CHECK_ERROR - Error checking file locks for {}: {}", filePath, e.getMessage());
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