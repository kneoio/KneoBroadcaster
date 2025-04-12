package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

import java.util.*;
import java.util.stream.Collectors;

@Getter
public class HLSPlaylistStats {
    private final Map<Integer, PlaylistFragmentRange> mainQueue;
    private final TreeMap<String, Integer> songRequestCounts = new TreeMap<>();

    public HLSPlaylistStats(Map<Integer, PlaylistFragmentRange> mainQueue) {
        this.mainQueue = mainQueue;
    }

    public Map<Long, HLSSongStats> getSongStatistics() {
        return mainQueue.values().stream()
                .map(range -> {
                    String songName = range.getFragment().getTitle();
                    Collection<HlsSegment> songSegments = range.getSegments().values();

                    int totalDuration = songSegments.stream().mapToInt(HlsSegment::getDuration).sum();
                    long totalSize = songSegments.stream().mapToLong(HlsSegment::getSize).sum();
                    int avgBitrate = songSegments.isEmpty() ? 0 :
                            (int) songSegments.stream().mapToInt(HlsSegment::getBitrate).average().orElse(0);
                    int requestCount = songRequestCounts.getOrDefault(songName, 0);

                    return new AbstractMap.SimpleEntry<>(range.getStart(),
                            new HLSSongStats(songName, range.getStart(), range.getEnd(), songSegments.size(),
                                    totalDuration, totalSize, avgBitrate, requestCount));
                })
                .sorted(Comparator.comparingLong(e -> e.getValue().getStart()))
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

}