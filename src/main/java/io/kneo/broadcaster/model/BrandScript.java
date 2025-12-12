package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Setter
@Getter
public class BrandScript {
    private UUID id;
    private UUID defaultBrandId;
    private int rank;
    private boolean active;
    private Script script;
    private List<UUID> representedInBrands;
    private Map<String, Object> userVariables;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrandScript that = (BrandScript) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
