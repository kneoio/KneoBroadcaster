package io.kneo.broadcaster.model.cnst;

public enum QueuePriority {
    LAST(10),
    HIGH(9),
    INTERRUPT(8),
    HARD_INTERRUPT(7);

    private final int value;

    QueuePriority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static QueuePriority fromValue(Integer v) {
        if (v == null) return null;
        for (QueuePriority p : values()) {
            if (p.value == v) return p;
        }
        return null;
    }
}
