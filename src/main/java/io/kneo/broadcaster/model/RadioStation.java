package io.kneo.broadcaster.model;

import io.kneo.broadcaster.controller.stream.HlsPlaylist;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class RadioStation extends SecureDataEntity<UUID> {
    private String brand;
    private HlsPlaylist playlist;
    private LocalDateTime created;
    private int listenersCount;


}
