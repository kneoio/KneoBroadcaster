package io.kneo.broadcaster.controller.stream;

import java.time.Duration;
import java.time.ZonedDateTime;

public record SlideEvent(
        SlideType type,
        ZonedDateTime timestamp,
        long sequenceNumber,
        Duration timingError,

        int currentKey,
        long currentRangeStart,
        long currentRangeEnd,
        String currentFragmentId,
        ZonedDateTime currentStaleTime,

        int nextKey,
        long nextRangeStart,
        long nextRangeEnd,
        String nextFragmentId

//        int futureKey,
//       long futureRangeStart,
//        long futureRangeEnd,
//        String futureFragmentId
) {
}