package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.live.SongMetadata;


public record HLSSongStats(SongMetadata songMetadata, long segmentTimestamp, long requestCount) {
}
