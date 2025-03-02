package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class BrandSoundFragment {
    private UUID id;
    private int playedByBrandCount;
    private LocalDateTime lastTimePlayedByBrand;
    private SoundFragment soundFragment;

}