package io.kneo.broadcaster.model.ai;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Tool {
    private ToolType toolType;
    private String name;
    private String variableName;
    private String description;

    public Tool() {}

    public Tool(String name, String description, String variableName) {
        this.name = name;
        this.description = description;
        this.variableName = variableName;
    }
}
