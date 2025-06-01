package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.AccessType;
import io.kneo.broadcaster.model.cnst.FileStorageType;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

@Setter
@Getter
public class FileMetadata {
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
}