package io.kneo.broadcaster.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class ListenerOfBrandDTO {
    private UUID id;
    private String listenerType;
}