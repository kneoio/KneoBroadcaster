package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@NoArgsConstructor
public class TreeNodeDTO {
    private String key;
    private String label;
    private boolean isLeaf;
    private String nodeType;
    private String entityId;
    private String openTarget;
}
