package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Setter
@Getter
public class AddToQueueMcpDTO {
    private MergingType mergingMethod;
    private Map<String, String> filePaths;
    private Map<String, UUID> soundFragments;
    private Integer priority = 100;
}