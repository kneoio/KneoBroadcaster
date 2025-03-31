package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;

@Getter
public class HlsSegmentStats {

    private final ConcurrentNavigableMap<Long, HlsSegment> segments;
    private final Instant createdAt = Instant.now();

    private long lastRequestedSegment = 0;
    private Instant lastRequestedTimestamp = Instant.now();
    private final long totalBytesProcessed = 0;
    private final int bitrate = 0;
    private final int queueSize = 0;
    private final Map<String, Integer> songRequestCounts = new HashMap<>();

    public HlsSegmentStats(ConcurrentNavigableMap<Long, HlsSegment> segments) {
        this.segments = segments;
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public Instant getNowTimestamp() {
        return Instant.now();
    }

    public void setLastRequestedSegment(long segmentId) {
        this.lastRequestedSegment = segmentId;
        this.lastRequestedTimestamp = Instant.now();
        HlsSegment segment = segments.get(segmentId);
        if (segment != null) {
            String songName = segment.getSongName();
            songRequestCounts.put(songName, songRequestCounts.getOrDefault(songName, 0) + 1);
        }
    }

    private Map<String, List<HlsSegment>> getSegmentsBySong() {
        Map<String, List<HlsSegment>> result = new HashMap<>();

        for (HlsSegment segment : segments.values()) {
            result.computeIfAbsent(segment.getSongName(), k -> new ArrayList<>()).add(segment);
        }

        return result;
    }

    public Map<String, SongStats> getSongStatistics() {
        Map<String, SongStats> stats = new HashMap<>();
        Map<String, List<HlsSegment>> segmentsBySong = getSegmentsBySong();

        for (Map.Entry<String, List<HlsSegment>> entry : segmentsBySong.entrySet()) {
            String songName = entry.getKey();
            List<HlsSegment> songSegments = entry.getValue();

            int totalDuration = songSegments.stream().mapToInt(HlsSegment::getDuration).sum();
            long totalSize = songSegments.stream().mapToLong(HlsSegment::getSize).sum();
            int avgBitrate = songSegments.isEmpty() ? 0 :
                    (int) songSegments.stream().mapToInt(HlsSegment::getBitrate).average().orElse(0);
            int requestCount = songRequestCounts.getOrDefault(songName, 0);

            stats.put(songName, new SongStats(songSegments.size(), totalDuration, totalSize, avgBitrate, requestCount));
        }

        return stats;
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

        @Override
        public String toString() {
            return String.format("segments=%d, duration=%ds, size=%d bytes, bitrate=%d kbps, requests=%d",
                    segmentCount, totalDuration, totalSize, averageBitrate / 1000, requestCount);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HlsSegmentStats: ");
        sb.append("segments=").append(segments.size()).append(", ");

        Map<String, Integer> songDurations = new HashMap<>();
        Map<String, Integer> songCounts = new HashMap<>();

        for (HlsSegment segment : segments.values()) {
            String songName = segment.getSongName();
            songCounts.put(songName, songCounts.getOrDefault(songName, 0) + 1);
            songDurations.put(songName, songDurations.getOrDefault(songName, 0) + segment.getDuration());
        }

        int totalDuration = songDurations.values().stream().mapToInt(Integer::intValue).sum();
        sb.append("totalDuration=").append(totalDuration).append("s");

        sb.append(", bitrate=").append(bitrate).append(" kbps");
        sb.append(", queueSize=").append(queueSize);
        sb.append(", lastRequested=").append(lastRequestedSegment);

        sb.append("\nSongs: ");
        for (String songName : songCounts.keySet()) {
            sb.append("\n  - ").append(songName)
                    .append(": segments=").append(songCounts.get(songName))
                    .append(", duration=").append(songDurations.get(songName)).append("s")
                    .append(", requests=").append(songRequestCounts.getOrDefault(songName, 0));
        }

        return sb.toString();
    }
}