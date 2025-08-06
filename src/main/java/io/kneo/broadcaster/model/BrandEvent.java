package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Setter
@Getter
public class BrandEvent {
    private UUID id;
    private Event event;
    private List<UUID> representedInBrands;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrandEvent that = (BrandEvent) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

}