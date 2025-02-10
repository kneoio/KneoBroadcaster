package io.kneo.broadcaster.dto;

import io.kneo.broadcaster.controller.stream.Playlist;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class RadioStationDTO extends SecureDataEntity<UUID> {
    private String brand;
    private Playlist playlist;
    private LocalDateTime created;
    private int listenersCount;


}
