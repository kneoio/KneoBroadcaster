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
    private final AtomicLong segmentTimeStamp = new AtomicLong(0);
    private final AtomicReference<CurrentFragmentInfo> currentFragmentInfo = new AtomicReference<>();

    private final ConcurrentLinkedQueue<PlaylistRange> mainQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();
    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();

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
        this.playlistManager = new PlaylistManager(soundFragmentService, segmentationService, brandName);
        this.playlistManager.start();
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
        AtomicBoolean isProcessing = processingFlags.computeIfAbsent(brandName, k -> new AtomicBoolean(false));
        if (isProcessing.get() || !isProcessing.compareAndSet(false, true)) {
            return;
        }

        try {
            if (segments.size() < config.getMinSegments()) {
                BrandSoundFragment fragment = playlistManager.getNextFragment();
                ConcurrentNavigableMap<Long, HlsSegment> newSegments = new ConcurrentSkipListMap<>();

                fragment.getSegments().forEach(segment -> {
                    long sequenceNumber = currentSequence.getAndIncrement();
                    newSegments.put(sequenceNumber, segment);
                });

                addSegments(newSegments);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing timer tick for brand {}: {}", brandName, e.getMessage(), e);
        } finally {
            isProcessing.set(false);
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
                    .append("#EXT-X-MEDIA-SEQUENCE:").append(range.start()).append("\n");

            Map<Long, HlsSegment> rangeSegments = segments.subMap(range.start(), true, range.end(), true);

            Map<UUID, List<Map.Entry<Long, HlsSegment>>> segmentsByTrack = new HashMap<>();
            // Group segments by fragment ID
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
        segments.clear();
        currentSequence.set(0);
        totalBytesProcessed.set(0);
    }
}