package io.kneo.broadcaster.dto.queue;

import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.UUID;

@Getter
@Setter
public class IntroPlusSong implements MergingMethod {
    private UUID soundFragmentUUID;
    private Path filePath;


}
