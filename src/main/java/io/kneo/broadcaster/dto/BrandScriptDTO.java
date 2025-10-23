package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
public class BrandScriptDTO {
    private UUID id;
    private UUID defaultBrandId;
    private int rank;
    private boolean active;
    @JsonProperty("script")
    private ScriptDTO scriptDTO;
    private List<UUID> representedInBrands;
}
