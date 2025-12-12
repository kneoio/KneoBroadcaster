package io.kneo.broadcaster.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ScriptVariable {
    private String name;
    private String description;
    private String type = "string";
    private boolean required = true;

    public ScriptVariable(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
