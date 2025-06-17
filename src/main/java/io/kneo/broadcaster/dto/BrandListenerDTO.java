package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class BrandListenerDTO {
    private UUID id;
    @JsonProperty("listener")
    private ListenerDTO listenerDTO;
    private int playedByBrandCount;
    private LocalDateTime lastTimePlayedByBrand;
}