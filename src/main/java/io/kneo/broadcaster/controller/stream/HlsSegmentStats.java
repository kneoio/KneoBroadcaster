package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

import java.time.Instant;
import java.util.*;

@Getter
public class HlsSegmentStats {

    private final Map<Integer, PlaylistRange> mainQueue;
    private final Instant createdAt = Instant.now();

    private long lastRequestedSegment = 0;
    private Instant lastRequestedTimestamp = Instant.now();
    private final long totalBytesProcessed = 0;
    private final int bitrate = 0;
    private final int queueSize = 0;
    private final TreeMap<String, Integer> songRequestCounts = new TreeMap<>();

    public HlsSegmentStats(Map<Integer, PlaylistRange> mainQueue) {
        this.mainQueue = mainQueue;
    }

    public int getSegmentCount() {
        return mainQueue.values().stream()
                .mapToInt(range -> range.segments().size())
                .sum();
    }

    public Instant getNowTimestamp() {
        return Instant.now();
    }

    public Map<String, SongStats> getSongStatistics() {
        Map<String, SongStats> stats = new HashMap<>();
        for (PlaylistRange range : mainQueue.values()) {
            String songName = range.fragment().getTitle();
            Collection<HlsSegment> songSegments = range.segments().values();

            int totalDuration = songSegments.stream().mapToInt(HlsSegment::getDuration).sum();
            long totalSize = songSegments.stream().mapToLong(HlsSegment::getSize).sum();
            int avgBitrate = songSegments.isEmpty() ? 0 :
                    (int) songSegments.stream().mapToInt(HlsSegment::getBitrate).average().orElse(0);
            int requestCount = songRequestCounts.getOrDefault(songName, 0);

            stats.put(songName, new SongStats(songSegments.size(), totalDuration, totalSize, avgBitrate, requestCount));
        }

        return stats;
    }

    public void setLastRequestedSegment(String songName) {
        songRequestCounts.put(songName, songRequestCounts.getOrDefault(songName, 0) + 1);
    }

    @Getter
    public static class SongStats {
        private final int segmentCount;
        private final int totalDuration;
        private final long totalSize;
        private final int averageBitrate;
        private final int requestCount;

        public SongStats(int segmentCount, int totalDuration, long totalSize, int averageBitrate, int requestCount) {
            this.segmentCount = segmentCount;
            this.totalDuration = totalDuration;
            this.totalSize = totalSize;
            this.averageBitrate = averageBitrate;
            this.requestCount = requestCount;
        }
    }
}