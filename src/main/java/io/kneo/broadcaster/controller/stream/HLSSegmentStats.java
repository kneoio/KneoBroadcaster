package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Getter
public class HLSSegmentStats {
    // ALL ORIGINAL FIELDS REMAIN UNCHANGED
    private final Map<Integer, PlaylistFragmentRange> mainQueue;
    private final Instant createdAt = Instant.now();
    private long lastRequestedSegment = 0;
    private Instant lastRequestedTimestamp = Instant.now();
    private final long totalBytesProcessed = 0;
    private final int bitrate = 0;
    private final int queueSize = 0;
    private final TreeMap<String, Integer> songRequestCounts = new TreeMap<>();

    public HLSSegmentStats(Map<Integer, PlaylistFragmentRange> mainQueue) {
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

    // ONLY CHANGE: Simplified sorting to use ONLY start time
    public Map<String, SongStats> getSongStatistics() {
        return mainQueue.values().stream()
                .map(range -> {
                    String songName = range.fragment().getTitle();
                    Collection<HlsSegment> songSegments = range.segments().values();

                    int totalDuration = songSegments.stream().mapToInt(HlsSegment::getDuration).sum();
                    long totalSize = songSegments.stream().mapToLong(HlsSegment::getSize).sum();
                    int avgBitrate = songSegments.isEmpty() ? 0 :
                            (int) songSegments.stream().mapToInt(HlsSegment::getBitrate).average().orElse(0);
                    int requestCount = songRequestCounts.getOrDefault(songName, 0);

                    return new AbstractMap.SimpleEntry<>(songName,
                            new SongStats(range.start(), range.end(), songSegments.size(),
                                    totalDuration, totalSize, avgBitrate, requestCount));
                })
                .sorted(Comparator.comparingLong(e -> e.getValue().getStart())) // ONLY START TIME
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    public void setLastRequestedSegment(String songName) {
        songRequestCounts.put(songName, songRequestCounts.getOrDefault(songName, 0) + 1);
    }

    // SongStats class REMAINS COMPLETELY UNCHANGED
    @Getter
    public static class SongStats {
        private final int segmentCount;
        private final int totalDuration;
        private final long totalSize;
        private final int averageBitrate;
        private final int requestCount;
        private final long start;
        private final long end;

        public SongStats(long start, long end, int segmentCount, int totalDuration,
                         long totalSize, int averageBitrate, int requestCount) {
            this.start = start;
            this.end = end;
            this.segmentCount = segmentCount;
            this.totalDuration = totalDuration;
            this.totalSize = totalSize;
            this.averageBitrate = averageBitrate;
            this.requestCount = requestCount;
        }
    }
}