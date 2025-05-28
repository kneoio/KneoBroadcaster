package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class FileMetadata {

    private Long id;
    private String parentTable;
    private UUID parentId;
    private int archived = 0;
    private LocalDateTime archivedDate;
    private String mimeType;
    private String cloudKey;
    private String cloudType;
    private byte[] fileBin;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

}