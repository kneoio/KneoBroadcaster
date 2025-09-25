package io.kneo.broadcaster.model.stats;

public record SegmentHeartbeat(boolean alive) {

    public static SegmentHeartbeat aliveBeat() {
        return new SegmentHeartbeat(true);
    }

    public static SegmentHeartbeat deadBeat() {
        return new SegmentHeartbeat(false);
    }
}
