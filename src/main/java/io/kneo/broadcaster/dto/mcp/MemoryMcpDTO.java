package io.kneo.broadcaster.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class MemoryMcpDTO {
    private List<String> requestedTypes;
    private Map<String, Object> memoryData;
}
