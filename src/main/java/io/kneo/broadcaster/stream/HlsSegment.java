
package io.kneo.broadcaster.stream;

import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.time.Instant;

public class HlsSegment {
    private final int sequenceNumber;
    private final byte[] data;
    private final long timestamp;
    private final int duration;

    public HlsSegment(int sequenceNumber, byte[] data, int duration) {
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.timestamp = Instant.now().getEpochSecond();
        this.duration = duration;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public byte[] getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getDuration() {
        return duration;
    }
}

