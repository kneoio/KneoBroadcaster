package io.kneo.broadcaster.service.manipulation.mixing;

import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.UUID;

@Getter
@Setter
public class IntroPlusSong {
    private UUID soundFragmentUUID;
    private Path filePath;

    public IntroPlusSong(UUID soundFragmentUUID, Path filePath) {
        this.soundFragmentUUID = soundFragmentUUID;
        this.filePath = filePath;
    }
}
