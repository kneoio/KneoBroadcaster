package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.FileData;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@ApplicationScoped
public class FileDownloadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileDownloadService.class);

    private final String uploadDir;
    private final SoundFragmentService soundFragmentService;

    @Inject
    public FileDownloadService(BroadcasterConfig config, SoundFragmentService soundFragmentService) {
        this.uploadDir = config.getPathUploads() + "/sound-fragments-controller";
        this.soundFragmentService = soundFragmentService;
    }

    public Uni<FileData> getFile(String id, String requestedFileName, IUser user) {
        try {
            if ("temp".equals(id)) {
                return getTempFile(requestedFileName, user);
            }

            return getEntityFile(id, requestedFileName, user);

        } catch (SecurityException | IllegalArgumentException e) {
            return Uni.createFrom().failure(e);
        }
    }

    private Uni<FileData> getTempFile(String requestedFileName, IUser user) {
        String safeFileName;
        try {
            safeFileName = FileSecurityUtils.sanitizeFilename(requestedFileName);
        } catch (SecurityException e) {
            LOGGER.warn("Unsafe filename in temp file request: {} from user: {}", requestedFileName, user.getUserName());
            return Uni.createFrom().failure(new SecurityException("Invalid filename"));
        }

        Path baseDir = Paths.get(uploadDir, user.getUserName(), "temp");
        Path secureFilePath = FileSecurityUtils.secureResolve(baseDir, safeFileName);

        if (!FileSecurityUtils.isPathWithinBase(baseDir, secureFilePath)) {
            LOGGER.error("Security violation: Path traversal attempt by user {} for temp file {}",
                    user.getUserName(), requestedFileName);
            return Uni.createFrom().failure(new SecurityException("Invalid file path"));
        }

        File file = secureFilePath.toFile();
        if (file.exists()) {
            try {
                Path canonicalFile = file.toPath().toRealPath();
                Path canonicalBase = baseDir.toRealPath();
                if (!canonicalFile.startsWith(canonicalBase)) {
                    LOGGER.error("Security violation: Temp file outside base directory accessed by user {}", user.getUserName());
                    return Uni.createFrom().failure(new SecurityException("File access denied"));
                }

                byte[] fileBytes = Files.readAllBytes(canonicalFile);
                String mimeType = Files.probeContentType(canonicalFile);
                return Uni.createFrom().item(new FileData(
                        fileBytes,
                        mimeType != null ? mimeType : "application/octet-stream"
                ));
            } catch (IOException e) {
                LOGGER.error("Temp file read error for user {}, file: {}", user.getUserName(), safeFileName, e);
                return Uni.createFrom().failure(e);
            }
        } else {
            return Uni.createFrom().failure(new FileNotFoundException("Temp file not found: " + safeFileName));
        }
    }

    private Uni<FileData> getEntityFile(String id, String requestedFileName, IUser user) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid entity ID in file request: {} from user: {}", id, user.getUserName());
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid entity ID"));
        }

        String safeFileName;
        try {
            safeFileName = FileSecurityUtils.sanitizeFilename(requestedFileName);
        } catch (SecurityException e) {
            LOGGER.warn("Unsafe filename in file request: {} from user: {}", requestedFileName, user.getUserName());
            return Uni.createFrom().failure(new SecurityException("Invalid filename"));
        }

        Path baseDir = Paths.get(uploadDir, user.getUserName(), id);
        Path secureFilePath = FileSecurityUtils.secureResolve(baseDir, safeFileName);

        if (!FileSecurityUtils.isPathWithinBase(baseDir, secureFilePath)) {
            LOGGER.error("Security violation: Path traversal attempt by user {} for file {}",
                    user.getUserName(), requestedFileName);
            return Uni.createFrom().failure(new SecurityException("Invalid file path"));
        }

        File file = secureFilePath.toFile();

        if (file.exists()) {
            try {
                Path canonicalFile = file.toPath().toRealPath();
                Path canonicalBase = baseDir.toRealPath();
                if (!canonicalFile.startsWith(canonicalBase)) {
                    LOGGER.error("Security violation: File outside base directory accessed by user {}", user.getUserName());
                    return Uni.createFrom().failure(new SecurityException("File access denied"));
                }

                byte[] fileBytes = Files.readAllBytes(canonicalFile);
                String mimeType = Files.probeContentType(canonicalFile);
                return Uni.createFrom().item(new FileData(
                        fileBytes,
                        mimeType != null ? mimeType : "application/octet-stream"
                ));
            } catch (IOException e) {
                LOGGER.error("File read error for user {}, file: {}", user.getUserName(), safeFileName, e);
                return Uni.createFrom().failure(e);
            }
        }

        return soundFragmentService.getFileBySlugName(UUID.fromString(id), safeFileName, user)
                .onItem().transform(fileMetadata ->
                        new FileData(fileMetadata.getFileBin(), fileMetadata.getMimeType()));
    }
}