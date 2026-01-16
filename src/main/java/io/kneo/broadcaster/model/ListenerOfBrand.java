package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class ListenerOfBrand {
    private UUID id;
    private String listenerType;
}