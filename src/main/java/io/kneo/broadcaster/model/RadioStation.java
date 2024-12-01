package io.kneo.broadcaster.model;

import io.kneo.broadcaster.controller.stream.HlsPlaylist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
@NoArgsConstructor
public class RadioStation {
    private String brand;
    private HlsPlaylist playlist;
    private LocalDateTime created;
    private int listenersCount;


}
