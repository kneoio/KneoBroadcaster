package io.kneo.broadcaster.model.ai;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Voice {
    private String id;

    public Voice(String id, String name) {
        this.id = id;
    }

}
