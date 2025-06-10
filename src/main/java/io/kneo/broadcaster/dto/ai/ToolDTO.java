package io.kneo.broadcaster.dto.ai;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ToolDTO {
    private String name;
    private String variableName;
    private String description;
}