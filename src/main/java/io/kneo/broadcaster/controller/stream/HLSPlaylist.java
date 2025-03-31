package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.CurrentFragmentInfo;
import io.kneo.broadcaster.model.RadioStation;
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
    private final ConcurrentLinkedQueue<PlaylistRange> mainQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicReference<CurrentFragmentInfo> currentFragmentInfo = new AtomicReference<>();


    private final Map<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();
    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();

    private final int minSegments;
    private final int maxSegments;

    @Getter
    private final List<Integer> segmentSizeHistory = new CopyOnWriteArrayList<>();
    private final int HISTORY_MAX_SIZE = 60; // Stores last 60 counts (5 minutes at 5-second intervals)
    private final AtomicLong lastRecordTime = new AtomicLong(0);
    private static final long RECORD_INTERVAL = 5000; // 5 seconds in milliseconds

    @Setter
    private RadioStation radioStation;

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
    @Getter
    private HlsSegmentStats stats;

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
        this.minSegments = config.getMinSegments();
        this.maxSegments = config.getMaxSegments();
        stats = new HlsSegmentStats(segments);
        LOGGER.info("Created HLSPlaylist for brand: {}", brandName);
    }

    public void initialize() {
        LOGGER.info("New broadcast initialized for {}", brandName);
        playlistManager = new PlaylistManager(this);
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
        if (!processingFlags.computeIfAbsent(brandName, k -> new AtomicBoolean(false))
                .compareAndSet(false, true)) {
            return;
        }

        try {
            final int currentSize = segments.size();
            long now = System.currentTimeMillis();

            if (now - lastRecordTime.get() >= RECORD_INTERVAL) {
                recordSegmentSize(currentSize);
                lastRecordTime.set(now);
                logSegmentStats();
            }

            if (currentSize > minSegments) {
                int segmentsToRemove = Math.min(
                        Math.max((int)(currentSize * 0.1), 1),
                        currentSize - minSegments
                );

                final CurrentFragmentInfo currentInfo = currentFragmentInfo.get();
                final Long currentPlayingSequence = currentInfo != null ? currentInfo.getSequence() : null;

                List<Long> oldestKeys = segments.keySet().stream()
                        .sorted()
                        .filter(key -> currentPlayingSequence == null || key < currentPlayingSequence - 5)
                        .limit(segmentsToRemove)
                        .toList();

                for (Long key : oldestKeys) {
                    segments.remove(key);
                }

                LOGGER.debug("Removed {} oldest segments. Current size: {}",
                        oldestKeys.size(), segments.size());
            }

            BrandSoundFragment fragment = playlistManager.getNextFragment();
            if (fragment != null) {
                if (segments.size() < maxSegments) {
                    ConcurrentNavigableMap<Long, HlsSegment> newSegments = new ConcurrentSkipListMap<>();
                    fragment.getSegments().forEach(segment -> {
                        newSegments.put(currentSequence.getAndIncrement(), segment);
                    });
                    addSegments(newSegments);
                    LOGGER.debug("Added fragment: {}", fragment.getSoundFragment().getMetadata());
                } else {
                    LOGGER.info("Segments at capacity ({}), not adding new fragment", maxSegments);
                }
            }

        } catch (Exception e) {
            LOGGER.error("Processing error for {}: {}", brandName, e.getMessage(), e);
        } finally {
            processingFlags.get(brandName).set(false);
        }
    }

    private void recordSegmentSize(int size) {
        if (segmentSizeHistory.size() >= HISTORY_MAX_SIZE) {
            segmentSizeHistory.remove(0);
        }
        segmentSizeHistory.add(size);
    }

    private void logSegmentStats() {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Segment stats - Current: {}, Min: {}, Max: {}",
                    segments.size(),
                    config.getMinSegments(),
                    config.getMaxSegments());
        }
    }

    public String generatePlaylist() {
        radioStation.setStatus(RadioStationStatus.ON_LINE);
        if (segments.isEmpty()) {
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
                    .append("#EXT-X-MEDIA-SEQUENCE:").append(range.start()).append("\n")
                    // Add program date time for internet radio synchronization
                    .append("#EXT-X-PROGRAM-DATE-TIME:").append(getFormattedDateTime()).append("\n");

            Map<Long, HlsSegment> rangeSegments = segments.subMap(range.start(), true, range.end(), true);
            Map<UUID, List<Map.Entry<Long, HlsSegment>>> segmentsByTrack = new HashMap<>();

            for (Map.Entry<Long, HlsSegment> entry : rangeSegments.entrySet()) {
                UUID fragmentId = entry.getValue().getSoundFragmentId();
                segmentsByTrack.computeIfAbsent(fragmentId, k -> new ArrayList<>()).add(entry);
            }

            boolean firstTrack = true;
            for (UUID fragmentId : segmentsByTrack.keySet()) {
                List<Map.Entry<Long, HlsSegment>> trackSegments = segmentsByTrack.get(fragmentId);
                trackSegments.sort(Comparator.comparing(e -> e.getValue().getTimestamp()));

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
                            .append("\n");

                    long segmentTimestamp = System.currentTimeMillis() -
                            (segments.lastKey() - entry.getKey()) * config.getSegmentDuration() * 1000;
                    playlist.append("#EXT-X-PROGRAM-DATE-TIME:")
                            .append(formatTimestamp(segmentTimestamp))
                            .append("\n");

                    playlist.append("segments/")
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

    private String formatTimestamp(long timestamp) {
        return java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
    }

    private String getFormattedDateTime() {
        return java.time.Instant.now()
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
    }

    public HlsSegment getSegment(long sequence) {
        HlsSegment segment = segments.get(sequence);
        stats.setLastRequestedSegment(sequence);
        currentFragmentInfo.set(CurrentFragmentInfo.from(sequence, segment));
        return segment;
    }

    private void addSegments(ConcurrentNavigableMap<Long, HlsSegment> newSegments) {
        if (newSegments.isEmpty()) return;
        segments.putAll(newSegments);
        mainQueue.add(new PlaylistRange(newSegments.firstKey(), newSegments.lastKey()));
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public long getLastSegmentKey() {
        return segments.isEmpty() ? 0L : segments.lastKey();
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

    public void shutdown() {
        LOGGER.info("Shutting down playlist for: {}", brandName);
        timerSubscriptions.forEach((brand, subscription) -> {
            if (subscription != null) subscription.cancel();
        });
        timerSubscriptions.clear();
        segments.clear();
        currentSequence.set(0);
        totalBytesProcessed.set(0);
        segmentSizeHistory.clear();
    }
}