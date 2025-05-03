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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Voice voice = (Voice) o;
        return id.equals(voice.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
