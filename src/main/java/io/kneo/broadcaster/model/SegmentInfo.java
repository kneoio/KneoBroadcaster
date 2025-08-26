package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.live.SongMetadata;

public record SegmentInfo(String path, SongMetadata songMetadata, int duration, int sequenceIndex) {

}
