package io.kneo.broadcaster.dto;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class UploadFileDTO {
    private String id;
    private String name;
    private String status; // "pending"|"uploading"|"finished"|"removed"|"error"
    private String url;
    private Integer percentage;
    private String batchId;
    private String thumbnailUrl;
    private String type;
    private String fullPath;
}