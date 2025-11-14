package io.kneo.broadcaster.model.live;

import io.kneo.broadcaster.service.stream.HlsSegment;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@Getter
@Setter
public class LiveSoundFragment {
    private UUID soundFragmentId;
    private Map<Long,ConcurrentLinkedQueue<HlsSegment>> segments;
    private int queueNum = 1000;
    private SongMetadata metadata;
    private boolean played;
    private Path sourceFilePath;
    private Integer priority = 100;

}