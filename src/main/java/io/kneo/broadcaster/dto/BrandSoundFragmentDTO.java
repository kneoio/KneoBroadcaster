package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
public class BrandSoundFragmentDTO {
    private UUID id;
    private UUID defaultBrandId;
    @JsonProperty("soundfragment")
    private SoundFragmentDTO soundFragmentDTO;
    private int playedByBrandCount;
    private LocalDateTime lastTimePlayedByBrand;
    private List<UUID> representedInBrands;
}