package io.kneo.broadcaster.controller.stream;

// HlsSegment.java
public class HlsSegment {
    private final int sequenceNumber;
    private final byte[] data;
    private final long timestamp;
    private final int duration;
    private final int bitrate;  // Added for audio quality tracking
    private final long size;    // Added for memory tracking

    public HlsSegment(int sequenceNumber, byte[] data, int duration) {
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
        this.duration = duration;
        this.size = data.length;
        this.bitrate = (int)(size * 8 / (duration * 1000.0)); // Calculate actual bitrate
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

    public int getBitrate() {
        return bitrate;
    }

    public long getSize() {
        return size;
    }

    public boolean isExpired(long maxAgeMs) {
        return System.currentTimeMillis() - timestamp > maxAgeMs;
    }
}

// Consider also adding these interfaces for better structure:

