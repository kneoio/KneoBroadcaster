package io.kneo.broadcaster.model;

import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.model.cnst.AccessType;
import io.kneo.broadcaster.model.cnst.FileStorageType;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

@Setter
@Getter
public class FileMetadata {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileMetadata.class);

    private Long id;
    private ZonedDateTime regDate;
    private ZonedDateTime lastModifiedDate;
    private String parentTable;
    private UUID parentId;
    private int archived = 0;
    private LocalDateTime archivedDate;
    private String mimeType;
    private String slugName;
    private String fileKey;
    private String fileOriginalName;
    private FileStorageType fileStorageType;
    private byte[] fileBin;
    private AccessType accessType;
    private Path filePath;
    private Path temporaryFilePath;
    private InputStream inputStream;
    private Long contentLength;

    public Uni<Path> materializeFileStream(String tempBaseDir) {
        return Uni.createFrom().item(() -> {
                    try {
                        String extension = getFileExtension(this.mimeType);
                        Path tempDir = Paths.get(tempBaseDir, "temp");
                        Files.createDirectories(tempDir);
                        Path tempFile = Files.createTempFile(tempDir, "temp_song_", extension);

                        LOGGER.debug("Creating temporary song file: {}", tempFile);

                        try (InputStream stream = this.inputStream;
                             FileOutputStream outputStream = new FileOutputStream(tempFile.toFile())) {

                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            long totalBytes = 0;

                            while ((bytesRead = stream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                                totalBytes += bytesRead;
                            }

                            LOGGER.debug("Temporary song file created successfully: {} bytes", totalBytes);
                        }

                        this.temporaryFilePath = tempFile;
                        return tempFile;
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to create temporary file from metadata", e);
                    }
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().transform(failure -> {
                    if (failure instanceof RuntimeException && failure.getCause() instanceof IOException) {
                        return new AudioMergeException("Failed to create temporary file", failure.getCause());
                    }
                    return new AudioMergeException("Failed to create temporary file", failure);
                });
    }

    private String getFileExtension(String mimeType) {
        if (mimeType == null) {
            return ".tmp";
        }

        return switch (mimeType.toLowerCase()) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/wave", "audio/vnd.wave" -> ".wav";
            case "audio/flac", "audio/x-flac" -> ".flac";
            case "audio/aac" -> ".aac";
            case "audio/ogg" -> ".ogg";
            case "audio/m4a" -> ".m4a";
            default -> ".tmp";
        };
    }
}