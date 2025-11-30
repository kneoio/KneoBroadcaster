package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.AudioMetadataDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    private static final long MAX_FILE_SIZE_BYTES = 200 * 1024 * 1024; //200 mb
    private static final String BULK_FOLDER_NAME = "bulk";
    private final String uploadDir;
    private final String uploadDirectory;
    private final AudioMetadataService audioMetadataService;
    private final SoundFragmentService soundFragmentService;
    public final ConcurrentHashMap<String, UploadFileDTO> uploadProgressMap = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, ConcurrentHashMap<String, UploadFileDTO>> bulkUploadProgressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> batchBrandIdMap = new ConcurrentHashMap<>();

    @Inject
    public FileUploadService(BroadcasterConfig config, AudioMetadataService audioMetadataService,
                             SoundFragmentService soundFragmentService) {
        this.uploadDir = config.getPathUploads() + "/sound-fragments-controller";
        this.uploadDirectory = config.getPathUploads();
        this.audioMetadataService = audioMetadataService;
        this.soundFragmentService = soundFragmentService;
    }

    public void validateUploadMeta(String originalFileName, String contentType) {
        if (!isValidAudioFile(originalFileName, contentType)) {
            throw new IllegalArgumentException("Unsupported file type. Only audio files are allowed: " +
                    String.join(", ", SUPPORTED_AUDIO_EXTENSIONS));
        }
    }

    public Uni<UploadFileDTO> processDirectStream(RoutingContext rc, String uploadId, String controllerKey, IUser user) {
        return processDirectStream(rc, uploadId, controllerKey, "temp", user, true);
    }

    public Uni<UploadFileDTO> processDirectStream(RoutingContext rc, String uploadId, String controllerKey, String entityId, IUser user, boolean extractMetadata) {
        return Uni.createFrom().<UploadFileDTO>emitter(emitter -> {
            try {
                rc.request().setExpectMultipart(true);

                rc.request().uploadHandler(upload -> {
                    try {
                        String originalFileName = upload.filename();
                        validateUploadMeta(originalFileName, upload.contentType());
                        String safeFileName = sanitizeAndValidateFilename(originalFileName, user);
                        Path destination = setupDirectoriesAndPath(controllerKey, entityId, user, safeFileName);
                        Path tempInFinalDir = destination.getParent().resolve(".tmp_" + uploadId + "_" + safeFileName);
                        upload.streamToFileSystem(tempInFinalDir.toString());

                        upload.endHandler(v -> {
                            try {
                                if (Files.size(tempInFinalDir) > MAX_FILE_SIZE_BYTES) {
                                    Files.deleteIfExists(tempInFinalDir);
                                    emitter.fail(new IllegalArgumentException(String.format("File too large. Maximum size is %d MB",
                                            MAX_FILE_SIZE_BYTES / 1024 / 1024)));
                                    return;
                                }
                                Files.move(tempInFinalDir, destination, StandardCopyOption.REPLACE_EXISTING);
                                String fileUrl = generateFileUrl(entityId, safeFileName);

                                AudioMetadataDTO metadata = null;
                                if (extractMetadata) {
                                    metadata = extractMetadata(destination, originalFileName, uploadId);
                                }

                                UploadFileDTO dto = UploadFileDTO.builder()
                                        .status("finished")
                                        .percentage(100)
                                        .batchId(uploadId)
                                        .name(safeFileName)
                                        .url(fileUrl)
                                        .metadata(metadata)
                                        .build();
                                emitter.complete(dto);
                            } catch (Exception e) {
                                try {
                                    Files.deleteIfExists(tempInFinalDir);
                                    Files.deleteIfExists(destination);
                                } catch (IOException ignored) {}
                                emitter.fail(e);
                            }
                        });

                        upload.exceptionHandler(err -> {
                            try {
                                Path userDir = Paths.get(uploadDir, controllerKey, user.getUserName(), entityId != null ? entityId : "temp");
                                Files.list(userDir)
                                        .filter(p -> p.getFileName().toString().startsWith(".tmp_" + uploadId + "_"))
                                        .forEach(p -> {
                                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                                        });
                            } catch (IOException ignored) {}
                            emitter.fail(err);
                        });

                    } catch (Exception e) {
                        emitter.fail(e);
                    }
                });
            } catch (Exception e) {
                emitter.fail(e);
            }
        }).emitOn(Infrastructure.getDefaultExecutor());
    }

    private Uni<Void> resolveBrandSlugIfNeeded(String batchId, String brandSlug) {
        if (brandSlug == null || brandSlug.trim().isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        
        // Check if already resolved for this batch
        if (batchBrandIdMap.containsKey(batchId)) {
            return Uni.createFrom().voidItem();
        }
        
        // Resolve brandSlug to UUID once
        return soundFragmentService.resolveBrandSlug(brandSlug)
                .map(brandId -> {
                    if (brandId != null) {
                        batchBrandIdMap.put(batchId, brandId);
                    }
                    return null;
                });
    }

    public Uni<UploadFileDTO> processDirectBulkStreamAsync(RoutingContext rc, String batchId, String fileId, String brandSlug, String controllerKey, IUser user) {
        // Resolve brandSlug once for the batch before processing
        return resolveBrandSlugIfNeeded(batchId, brandSlug)
                .chain(() -> processFileUpload(rc, batchId, fileId, controllerKey, user));
    }

    private Uni<UploadFileDTO> processFileUpload(RoutingContext rc, String batchId, String fileId, String controllerKey, IUser user) {
        return Uni.createFrom().<UploadFileDTO>emitter(emitter -> {
            try {
                rc.request().setExpectMultipart(true);

                rc.request().uploadHandler(upload -> {
                    try {
                        String originalFileName = upload.filename();
                        validateUploadMeta(originalFileName, upload.contentType());
                        String safeFileName = sanitizeAndValidateFilename(originalFileName, user);
                        Path destination = setupDirectoriesAndPath(controllerKey, BULK_FOLDER_NAME, user, safeFileName);
                        Path tempInFinalDir = destination.getParent().resolve(".tmp_" + batchId + "_" + safeFileName);
                        upload.streamToFileSystem(tempInFinalDir.toString());

                        upload.endHandler(v -> {
                            try {
                                if (Files.size(tempInFinalDir) > MAX_FILE_SIZE_BYTES) {
                                    Files.deleteIfExists(tempInFinalDir);
                                    emitter.fail(new IllegalArgumentException(String.format("File too large. Maximum size is %d MB",
                                            MAX_FILE_SIZE_BYTES / 1024 / 1024)));
                                    return;
                                }
                                Files.move(tempInFinalDir, destination, StandardCopyOption.REPLACE_EXISTING);
                                String fileUrl = generateFileUrl(BULK_FOLDER_NAME, safeFileName);

                                // Get or create batch map once
                                ConcurrentHashMap<String, UploadFileDTO> batchMap = bulkUploadProgressMap.computeIfAbsent(batchId, k -> new ConcurrentHashMap<>());

                                // Return immediately with processing status
                                UploadFileDTO dto = UploadFileDTO.builder()
                                        .id(fileId)
                                        .status("processing")
                                        .percentage(100)
                                        .batchId(batchId)
                                        .name(safeFileName)
                                        .url(fileUrl)
                                        .build();
                                
                                batchMap.put(fileId, dto);
                                emitter.complete(dto);

                                // Extract metadata async in background
                                Uni.createFrom().item(() -> {
                                    AudioMetadataDTO metadata = extractMetadata(destination, originalFileName, fileId);
                                    UploadFileDTO metadataDto = UploadFileDTO.builder()
                                            .id(fileId)
                                            .status("metadata_extracted")
                                            .percentage(100)
                                            .batchId(batchId)
                                            .name(safeFileName)
                                            .url(fileUrl)
                                            .fullPath(destination.toString())
                                            .metadata(metadata)
                                            .build();
                                    
                                    batchMap.put(fileId, metadataDto);
                                    return metadataDto;
                                }).runSubscriptionOn(Infrastructure.getDefaultExecutor())
                                .chain(metadataDto -> {
                                    // Create SoundFragment entity
                                    UploadFileDTO creatingDto = UploadFileDTO.builder()
                                            .id(fileId)
                                            .status("creating_entity")
                                            .percentage(100)
                                            .batchId(batchId)
                                            .name(safeFileName)
                                            .url(fileUrl)
                                            .fullPath(metadataDto.getFullPath())
                                            .metadata(metadataDto.getMetadata())
                                            .build();
                                    batchMap.put(fileId, creatingDto);
                                    
                                    // Get cached brandId for this batch
                                    UUID brandId = batchBrandIdMap.get(batchId);
                                    
                                    return soundFragmentService.createFromBulkUpload(metadataDto, brandId, user)
                                            .map(fragment -> {
                                                UploadFileDTO finalDto = UploadFileDTO.builder()
                                                        .id(fileId)
                                                        .status("finished")
                                                        .percentage(100)
                                                        .batchId(batchId)
                                                        .name(safeFileName)
                                                        .url(fileUrl)
                                                        .metadata(metadataDto.getMetadata())
                                                        .build();
                                                batchMap.put(fileId, finalDto);
                                                return finalDto;
                                            });
                                })
                                .subscribe().with(
                                    result -> LOGGER.info("Bulk upload completed for fileId: {}", fileId),
                                    err -> {
                                        LOGGER.error("Bulk upload failed for fileId: {}", fileId, err);
                                        UploadFileDTO errorDto = UploadFileDTO.builder()
                                                .id(fileId)
                                                .status("error")
                                                .percentage(100)
                                                .batchId(batchId)
                                                .name(safeFileName)
                                                .url(fileUrl)
                                                .errorMessage("Upload failed: " + err.getMessage())
                                                .build();
                                        
                                        batchMap.put(fileId, errorDto);
                                    }
                                );

                            } catch (Exception e) {
                                try {
                                    Files.deleteIfExists(tempInFinalDir);
                                    Files.deleteIfExists(destination);
                                } catch (IOException ignored) {}
                                emitter.fail(e);
                            }
                        });

                        upload.exceptionHandler(err -> {
                            try {
                                Path userDir = Paths.get(uploadDirectory, controllerKey, user.getUserName(), BULK_FOLDER_NAME);
                                Files.list(userDir)
                                        .filter(p -> p.getFileName().toString().startsWith(".tmp_" + batchId + "_"))
                                        .forEach(p -> {
                                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                                        });
                            } catch (IOException ignored) {}
                            emitter.fail(err);
                        });

                    } catch (Exception e) {
                        emitter.fail(e);
                    }
                });
            } catch (Exception e) {
                emitter.fail(e);
            }
        }).emitOn(Infrastructure.getDefaultExecutor());
    }

    public ConcurrentHashMap<String, UploadFileDTO> getBulkUploadProgress(String batchId) {
        ConcurrentHashMap<String, UploadFileDTO> files = bulkUploadProgressMap.get(batchId);
        return files != null ? files : new ConcurrentHashMap<>();
    }

    private String sanitizeAndValidateFilename(String originalFileName, IUser user) {
        try {
            return FileSecurityUtils.sanitizeFilename(originalFileName);
        } catch (SecurityException e) {
            LOGGER.warn("Unsafe filename rejected: {} from user: {}", originalFileName, user.getUserName());
            throw new IllegalArgumentException("Invalid filename: " + e.getMessage());
        }
    }

    private Path setupDirectoriesAndPath(String controllerKey, String entityId, IUser user, String safeFileName) throws Exception {
        if (!"temp".equals(entityId) && !"bulk".equals(entityId)) {
            try {
                UUID.fromString(entityId);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid entity ID: {}, controller: {}", entityId, controllerKey);
                throw new IllegalArgumentException("Invalid entity ID");
            }
        }

        Path userDir = Files.createDirectories(Paths.get(uploadDirectory, controllerKey, user.getUserName()));
        Path entityDir = Files.createDirectories(userDir.resolve(entityId));
        Path destination = FileSecurityUtils.secureResolve(entityDir, safeFileName);
        Path expectedBase = Paths.get(uploadDirectory, controllerKey, user.getUserName(), entityId);

        if (!FileSecurityUtils.isPathWithinBase(expectedBase, destination)) {
            LOGGER.error("Security violation: Path traversal attempt with filename {}", safeFileName);
            throw new SecurityException("Invalid file path");
        }

        return destination;
    }

    private AudioMetadataDTO extractMetadata(Path destination, String originalFileName, String uploadId) {
        if (!isValidAudioFile(originalFileName, null)) {
            LOGGER.info("Skipping metadata extraction for non-audio file: {}", originalFileName);
            updateProgress(uploadId, 90, "extract_metadata", null, null, null, null);
            return null;
        }

        try {
            LOGGER.info("Extracting metadata for file: {}", destination);
            updateProgress(uploadId, 75, "extract_metadata", null, null, null, null);

            AudioMetadataDTO metadata = audioMetadataService.extractMetadataWithProgress(
                    destination.toString(),
                    (percentage) -> {
                        int overallProgress = 75 + (percentage * 15 / 100);
                        updateProgress(uploadId, Math.min(overallProgress, 90), "extract_metadata", null, null, null, null);
                    }
            );

            LOGGER.info("Metadata extraction completed for uploadId: {}", uploadId);
            return metadata;
        } catch (Exception e) {
            LOGGER.warn("Metadata extraction failed for uploadId: {}, error: {}", uploadId, e.getMessage());
            updateProgress(uploadId, 90, "extract_metadata", null, null, null, null);
            return null;
        }
    }

    private String generateFileUrl(String entityId, String safeFileName) {
        String entityIdSafe = entityId != null ? entityId : "temp";
        return String.format("/api/soundfragments/files/%s/%s", entityIdSafe, safeFileName);
    }

    private void updateProgress(String uploadId, Integer percentage, String status, String url, String fullPath, AudioMetadataDTO metadata, String errorMessage) {
        UploadFileDTO dto = uploadProgressMap.get(uploadId);
        if (dto == null) {
            UploadFileDTO init = UploadFileDTO.builder()
                    .status(status != null ? status : "uploading")
                    .percentage(percentage != null ? percentage : 0)
                    .batchId(uploadId)
                    .build();
            uploadProgressMap.put(uploadId, init);
            dto = init;
        }

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
                .errorMessage(errorMessage != null ? errorMessage : dto.getErrorMessage())
                .build();

        uploadProgressMap.put(uploadId, updatedDto);
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


    private static final Set<String> SUPPORTED_AUDIO_EXTENSIONS = Set.of(
            "mp3", "wav", "flac", "aac", "ogg", "m4a"
    );

    private static final Set<String> SUPPORTED_AUDIO_MIME_TYPES = Set.of(
            "audio/mpeg", "audio/wav", "audio/wave", "audio/x-wav",
            "audio/flac", "audio/x-flac", "audio/aac", "audio/ogg",
            "audio/mp4", "audio/x-m4a"
    );
}