package io.kneo.broadcaster.service;

import java.util.UUID;

public record SegmentInfo(String path, String metadata, UUID fragmentId, int duration, int sequenceIndex) {

}
