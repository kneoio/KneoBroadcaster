package io.kneo.broadcaster.model.cnst;

import lombok.Getter;

@Getter
public enum MemoryType {
    UNKNOWN("unknown"),
    EVENT("events");

    private final String value;

    MemoryType(String value) {
        this.value = value;
    }

    public static MemoryType fromValue(String value) {
        for (MemoryType type : MemoryType.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    @Override
    public String toString() {
        return value;
    }
}