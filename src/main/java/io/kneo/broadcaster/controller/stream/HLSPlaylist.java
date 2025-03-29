package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.CurrentFragmentInfo;
import io.kneo.broadcaster.model.stats.PlaylistStats;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.kneo.broadcaster.service.stream.TimerService;
import io.smallrye.mutiny.subscription.Cancellable;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class HLSPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private final ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicReference<CurrentFragmentInfo> currentFragmentInfo = new AtomicReference<>();

    private final ConcurrentLinkedQueue<PlaylistRange> mainQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();
    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();

    @Getter
    private final ConcurrentLinkedQueue<SegmentSizeSnapshot> segmentSizeHistory = new ConcurrentLinkedQueue<>();

    @Getter
    @Setter
    private String brandName;

    @Getter
    private final SoundFragmentService soundFragmentService;

    @Getter
    private PlaylistManager playlistManager;

    @Getter
    private final AudioSegmentationService segmentationService;

    @Getter
    private final HlsPlaylistConfig config;

    @Getter
    private TimerService timerService;

    public HLSPlaylist(
            TimerService timerService,
            HlsPlaylistConfig config,
            SoundFragmentService soundFragmentService,
            AudioSegmentationService segmentationService,
            String brandName) {
        this.timerService = timerService;
        this.config = config;
        this.brandName = brandName;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = segmentationService;
        LOGGER.info("Created HLSPlaylist for brand: {}", brandName);
    }

    public void initialize() {
        LOGGER.info("New broadcast initialized for {}", brandName);
        playlistManager = new PlaylistManager(config, soundFragmentService, segmentationService, brandName);
        playlistManager.start();
        startMaintenanceService();
    }

    private void startMaintenanceService() {
        LOGGER.info("Initializing maintenance for playlist: {}", brandName);
        Cancellable subscription = timerService.getTicker().subscribe().with(
                timestamp -> processTimerTick(brandName, timestamp),
                error -> LOGGER.error("Timer subscription error for brand {}: {}", brandName, error.getMessage())
        );
        timerSubscriptions.put(brandName, subscription);
    }

    private void processTimerTick(String brandName, long timestamp) {
        // Skip if already processing
        if (!processingFlags.computeIfAbsent(brandName, k -> new AtomicBoolean(false))
                .compareAndSet(false, true)) {
            return;
        }

        try {
            final int currentSize = segments.size();
            final int minSegments = config.getMinSegments();
            final int maxSegments = config.getMaxSegments();

            // Track segment size
            recordSegmentSize(currentSize, timestamp);

            // Clean up old segment size history
            cleanupSegmentSizeHistory();

            // 1. Fetch new segments if below minimum threshold
            if (currentSize < minSegments) {
                BrandSoundFragment fragment = playlistManager.getNextFragment();
                if (fragment != null) {
                    ConcurrentNavigableMap<Long, HlsSegment> newSegments = new ConcurrentSkipListMap<>();
                    fragment.getSegments().forEach(segment -> {
                        newSegments.put(currentSequence.getAndIncrement(), segment);
                    });
                    addSegments(newSegments);
                }
            }
            // 2. Gradual cleanup if exceeding max threshold
            else if (currentSize > maxSegments) {
                // Calculate how many to delete (capped at 10% of current size)
                int segmentsToDelete = Math.min(
                        currentSize - minSegments,
                        Math.max(5, currentSize / 10)  // Delete at least 5, but no more than 10%
                );

                // Get the key at the deletion boundary
                Long oldestToKeep = segments.keySet().stream()
                        .skip(segmentsToDelete)
                        .findFirst()
                        .orElse(null);

                if (oldestToKeep != null) {
                    segments.headMap(oldestToKeep, false).clear();
                    LOGGER.debug("Cleaned {} segments (kept {} to {})",
                            segmentsToDelete, oldestToKeep, segments.lastKey());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Processing error for {}: {}", brandName, e.getMessage(), e);
        } finally {
            processingFlags.get(brandName).set(false);
        }
    }

    private void recordSegmentSize(int size, long timestamp) {
        long roundedTimestamp = (timestamp / 1000) * 1000;
        segmentSizeHistory.add(new SegmentSizeSnapshot(roundedTimestamp, size));
    }

    private void cleanupSegmentSizeHistory() {
        long currentTime = System.currentTimeMillis();
        int segmentSizeRetentionMinutes = 5;
        long cutoffTime = currentTime - (segmentSizeRetentionMinutes * 60 * 1000);

        while (!segmentSizeHistory.isEmpty() && segmentSizeHistory.peek().timestamp() < cutoffTime) {
            segmentSizeHistory.poll();
        }
    }

    public String generatePlaylist() {
        if (segments.isEmpty()) {
            LOGGER.warn("Attempted to generate playlist with no segments");
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n#EXT-X-TARGETDURATION:" +
                    config.getSegmentDuration() + "\n#EXT-X-MEDIA-SEQUENCE:" + currentSequence.get() +
                    "\n#EXT-X-DISCONTINUITY-SEQUENCE:" + (currentSequence.get() / 1000) + "\n";
        }

        PlaylistRange range = mainQueue.poll();
        if (range != null) {
            StringBuilder playlist = new StringBuilder(1000);
            playlist.append("#EXTM3U\n")
                    .append("#EXT-X-VERSION:3\n")
                    .append("#EXT-X-ALLOW-CACHE:NO\n")
                    .append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
                    .append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES\n")
                    .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                    .append("#EXT-X-STREAM-INF:BANDWIDTH=64000\n")
                    .append("#EXT-X-MEDIA-SEQUENCE:").append(range.start()).append("\n");

            Map<Long, HlsSegment> rangeSegments = segments.subMap(range.start(), true, range.end(), true);

            Map<UUID, List<Map.Entry<Long, HlsSegment>>> segmentsByTrack = new HashMap<>();
            for (Map.Entry<Long, HlsSegment> entry : rangeSegments.entrySet()) {
                UUID fragmentId = entry.getValue().getSoundFragmentId();
                if (!segmentsByTrack.containsKey(fragmentId)) {
                    segmentsByTrack.put(fragmentId, new ArrayList<>());
                }
                segmentsByTrack.get(fragmentId).add(entry);
            }

            // For each track, add all its segments in sequence
            boolean firstTrack = true;
            for (UUID fragmentId : segmentsByTrack.keySet()) {
                List<Map.Entry<Long, HlsSegment>> trackSegments = segmentsByTrack.get(fragmentId);

                // Sort segments by timestamp to ensure proper sequence
                trackSegments.sort(Comparator.comparing(e -> e.getValue().getTimestamp()));

                // Add discontinuity marker between tracks, but not before the first track
                if (!firstTrack) {
                    playlist.append("#EXT-X-DISCONTINUITY\n");
                }
                firstTrack = false;

                for (Map.Entry<Long, HlsSegment> entry : trackSegments) {
                    HlsSegment segment = entry.getValue();

                    playlist.append("#EXTINF:")
                            .append(segment.getDuration())
                            .append(",")
                            .append(segment.getSongName())
                            .append("\n")
                            .append("segments/")
                            .append(brandName)
                            .append("_")
                            .append(segment.getSoundFragmentId().toString(), 0, 8)
                            .append("_")
                            .append(segment.getTimestamp())
                            .append(".ts\n");
                }
            }

            return playlist.toString();
        } else {
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n#EXT-X-TARGETDURATION:" +
                    config.getSegmentDuration() + "\n#EXT-X-MEDIA-SEQUENCE:" + currentSequence.get() +
                    "\n#EXT-X-DISCONTINUITY-SEQUENCE:" + (currentSequence.get() / 1000) + "\n";
        }
    }

    public HlsSegment getSegment(long sequence) {
        HlsSegment segment = segments.get(sequence);
        playlistManager.setCurrentlyPlaying(segment.getSongName());
        currentFragmentInfo.set(CurrentFragmentInfo.from(sequence, segment));
        return segment;
    }


    private void addSegments(ConcurrentNavigableMap<Long, HlsSegment> newSegments) {
        if (newSegments.isEmpty()) {
            return;
        }
        segments.putAll(newSegments);
        long start = newSegments.firstKey();
        long end = newSegments.lastKey();
        PlaylistRange range = new PlaylistRange(start, end);
        mainQueue.add(range);
        LOGGER.info("Added segments with range: {} - {}", start, end);
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public long getLastSegmentKey() {
        ConcurrentNavigableMap<Long, HlsSegment> map = segments;
        return map.isEmpty() ? 0L : map.lastKey();
    }

    public PlaylistStats getStats() {
        return PlaylistStats.fromPlaylist(this, currentFragmentInfo.get());
    }

    public int getQueueSize() {
        return mainQueue.size();
    }

    public long getTotalBytesProcessed() {
        return totalBytesProcessed.get();
    }

    public Set<Long> getSegmentKeys() {
        return new HashSet<>(segments.keySet());
    }

    public void removeSegmentsBefore(Long oldestToKeep) {
        segments.headMap(oldestToKeep).clear();
    }

    public void shutdown() {
        LOGGER.info("Shutting down playlist for: {}", brandName);
        timerSubscriptions.forEach((brand, subscription) -> {
            if (subscription != null) {
                subscription.cancel();
                LOGGER.info("Cancelled timer subscription for brand: {}", brand);
            }
        });
        timerSubscriptions.clear();
        segments.clear();
        currentSequence.set(0);
        totalBytesProcessed.set(0);
        segmentSizeHistory.clear();
    }

    public record SegmentSizeSnapshot(long timestamp, int size) {
        @Override
        public String toString() {
            return String.format("[%tc: %d segments]", new Date(timestamp), size);
        }
    }
}