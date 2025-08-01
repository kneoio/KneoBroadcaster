package io.kneo.broadcaster.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
public class BulkBrandUpdateDTO {
    private List<UUID> documentIds;
    private List<String> brands;
    private String operation;

}
