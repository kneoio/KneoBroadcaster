package io.kneo.broadcaster.model;

import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Script extends SecureDataEntity<UUID> {
    private String name;
    private String description;
    private Integer archived;
    private List<UUID> labels;
    private List<UUID> brands;
}