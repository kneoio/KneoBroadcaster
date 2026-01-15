package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.ListenerType;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class BrandListener {
    private UUID id;
    private Listener listener;
    private ListenerType listenerType;
}