package io.kneo.broadcaster.controller.stream;

import java.time.Duration;
import java.time.ZonedDateTime;

public record SlideEvent(
        SlideType type,
        ZonedDateTime timestamp,
        long sequenceNumber,
        Duration timingError,

        // Current fragment info
        int currentKey,          // Changed from long to int
        long currentRangeStart,
        long currentRangeEnd,
        String currentFragmentId,
        ZonedDateTime currentStaleTime,

        // Next fragment info
        int nextKey,             // Changed from long to int
        long nextRangeStart,
        long nextRangeEnd,
        String nextFragmentId,

        // Future fragment info
        int futureKey,           // Changed from long to int
        long futureRangeStart,
        long futureRangeEnd,
        String futureFragmentId
) {
    @Override
    public String toString() {
        return String.format(
                "SlideEvent[type=%s, timestamp=%s, sequence=%d, timingError=%s," +
                        "\n  current: key=%d, range=[%d-%d], fragment=%s, staleTime=%s," +
                        "\n  next: key=%d, range=[%d-%d], fragment=%s," +
                        "\n  future: key=%d, range=[%d-%d], fragment=%s]",
                type, timestamp, sequenceNumber, timingError,
                currentKey, currentRangeStart, currentRangeEnd, currentFragmentId, currentStaleTime,
                nextKey, nextRangeStart, nextRangeEnd, nextFragmentId,
                futureKey, futureRangeStart, futureRangeEnd, futureFragmentId
        );
    }
}