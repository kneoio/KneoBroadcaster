package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.dto.queue.MergingMethod;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AddToQueueMcpDTO {
    private String brandName;
    private MergingMethod mergingMethod;
    private Integer priority = 10;

}