package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class BrandListenerDTO {
    private UUID id;
    @JsonProperty("listener")
    private ListenerDTO listenerDTO;
    private String listenerType;
}