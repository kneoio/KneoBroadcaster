package io.kneo.broadcaster.dto.queue;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AddToQueueDTO {
    private String brandName;
    private MergingMethod mergingMethod;
    private Integer priority = 10;

}