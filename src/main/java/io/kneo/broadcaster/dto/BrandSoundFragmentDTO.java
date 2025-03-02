package io.kneo.broadcaster.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class BrandSoundFragmentDTO {
    private UUID id;
    private SoundFragmentDTO soundFragmentDTO;
    private int playedByBrandCount;
    private LocalDateTime lastTimePlayedByBrand;
}