package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.PlaylistItem;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.stats.PlaylistStats;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.radio.PlaylistScheduler;
import io.kneo.broadcaster.service.stream.HlsTimerService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reactive HLS Playlist implementation that continuously generates segments
 * aligned with wall clock time regardless of active listeners
 */
public class ReactiveHlsPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveHlsPlaylist.class);

    // Segment store with timestamp-based keys for fast access
    private final ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();

    // Media sequence number (HLS playlist specific)
    private final AtomicInteger mediaSequence = new AtomicInteger(0);

    // Statistics tracking
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicLong lastRequestedTimestamp = new AtomicLong(0);

    // Recently played segments for tracking (limited size)
    private final LinkedList<String> recentlyPlayedTitles = new LinkedList<>();
    private final int MAX_RECENT_TITLES = 20;

    // Store segment names by ID for quick lookups
    private final Map<String, String> segmentTitles = new ConcurrentHashMap<>();

    @Getter
    @Setter
    private String stationId;

    // Subscriptions to keep active
    private Cancellable segmentGeneratorSubscription;
    private Cancellable maintenanceSubscription;

    // Services
    private final HlsTimerService timerService;
    private final AudioSegmentationService segmentationService;
    private final SoundFragmentService soundFragmentService;
    private final PlaylistScheduler playlistScheduler;
    private final HlsPlaylistConfig config;

    @Inject
    public ReactiveHlsPlaylist(
            HlsTimerService timerService,
            HlsPlaylistConfig config,
            AudioSegmentationService segmentationService,
            SoundFragmentService soundFragmentService,
            PlaylistScheduler playlistScheduler,
            String stationId) {
        this.timerService = timerService;
        this.config = config;
        this.segmentationService = segmentationService;
        this.soundFragmentService = soundFragmentService;
        this.playlistScheduler = playlistScheduler;
        this.stationId = stationId;
    }

    @PostConstruct
    public void initialize() {
        LOGGER.info("Initializing reactive HLS playlist for station: {}", stationId);

        // Register with playlist scheduler
        playlistScheduler.registerPlaylist(this);

        // Subscribe to the timer to generate segments continuously
        segmentGeneratorSubscription = timerService.getTicker()
                .onItem().transformToUni(timestamp -> {
                    // This will generate a segment for each timer tick
                    return generateSegmentForTimestamp(timestamp);
                })
                .concatenate()
                .subscribe().with(
                        segmentInfo -> LOGGER.debug("Generated segment: timestamp={}, title={}",
                                segmentInfo.timestamp, segmentInfo.title),
                        error -> LOGGER.error("Error generating segment", error)
                );

        // Schedule maintenance tasks (cleanup old segments, ensure content)
        setupMaintenanceTasks();

        LOGGER.info("Reactive HLS playlist initialized for station: {}", stationId);
    }

    /**
     * Generate HLS playlist with proper timing information
     */
    public String generatePlaylist() {
        if (segments.isEmpty()) {
            LOGGER.warn("Attempted to generate playlist with no segments for station: {}", stationId);
            return createEmptyPlaylist();
        }

        StringBuilder playlist = new StringBuilder(1000);
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-ALLOW-CACHE:NO\n")
                .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                .append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence.get()).append("\n");

        // Get window of segments to include (limited by config)
        int segmentWindow = config.getSegmentWindow();
        List<Long> keys = new LinkedList<>(segments.keySet());
        int startIndex = Math.max(0, keys.size() - segmentWindow);

        for (int i = startIndex; i < keys.size(); i++) {
            Long timestamp = keys.get(i);
            HlsSegment segment = segments.get(timestamp);

            // Add program date time for accurate timing
            Instant segmentTime = Instant.ofEpochSecond(timestamp);
            playlist.append("#EXT-X-PROGRAM-DATE-TIME:")
                    .append(DateTimeFormatter.ISO_INSTANT.format(segmentTime))
                    .append("\n");

            // Add segment info
            playlist.append("#EXTINF:")
                    .append(segment.getDuration())
                    .append(",")
                    .append(segment.getSongName())
                    .append("\n");

            // Use time-based naming for segments
            playlist.append("segments/")
                    .append(stationId)
                    .append("_")
                    .append(timestamp)
                    .append(".ts\n");
        }

        return playlist.toString();
    }

    /**
     * Get a segment by its timestamp
     */
    public HlsSegment getSegment(long timestamp) {
        HlsSegment segment = segments.get(timestamp);
        if (segment != null) {
            lastRequestedTimestamp.set(timestamp);
        }
        return segment;
    }

    /**
     * Get statistics about this playlist
     */
    public PlaylistStats getStats() {
        synchronized (recentlyPlayedTitles) {
            return PlaylistStats.fromPlaylist(this, new LinkedList<>(recentlyPlayedTitles));
        }
    }

    /**
     * Get the number of segments currently in the playlist
     */
    public int getSegmentCount() {
        return segments.size();
    }

    /**
     * Get the total bytes processed by this playlist
     */
    public long getTotalBytesProcessed() {
        return totalBytesProcessed.get();
    }

    /**
     * Add a segment to the playlist
     */
    private void addSegment(HlsSegment segment) {
        if (segment == null) {
            LOGGER.warn("Attempted to add null segment");
            return;
        }

        long timestamp = segment.getTimestamp();
        segments.put(timestamp, segment);
        totalBytesProcessed.addAndGet(segment.getSize());

        // Track title for recently played
        String title = segment.getSongName();
        segmentTitles.put(String.valueOf(timestamp), title);

        synchronized (recentlyPlayedTitles) {
            if (!recentlyPlayedTitles.contains(title)) {
                recentlyPlayedTitles.addLast(title);
                if (recentlyPlayedTitles.size() > MAX_RECENT_TITLES) {
                    recentlyPlayedTitles.removeFirst();
                }
            }
        }

        // Adjust media sequence if needed (when cleaning up old segments)
        long oldestTimestamp = segments.firstKey();
        long expectedOldestTimestamp = timestamp - (config.getSegmentWindow() * config.getSegmentDuration());

        if (oldestTimestamp > expectedOldestTimestamp) {
            // Update media sequence to account for removed segments
            long removedSegments = (oldestTimestamp - expectedOldestTimestamp) / config.getSegmentDuration();
            if (removedSegments > 0) {
                mediaSequence.addAndGet((int) removedSegments);
            }
        }
    }

    /**
     * Generate a segment for a specific timestamp
     */
    private io.smallrye.mutiny.Uni<SegmentInfo> generateSegmentForTimestamp(long timestamp) {
        // Check if we already have this segment (shouldn't happen with proper ticker)
        if (segments.containsKey(timestamp)) {
            LOGGER.warn("Segment already exists for timestamp: {}", timestamp);
            HlsSegment existing = segments.get(timestamp);
            return io.smallrye.mutiny.Uni.createFrom().item(
                    new SegmentInfo(timestamp, existing.getSongName())
            );
        }

        // Get sound fragment from service
        return soundFragmentService.getNextFragmentForStation(stationId)
                .onItem().transformToUni(fragment -> {
                    if (fragment == null) {
                        LOGGER.warn("No sound fragment available for station: {}", stationId);
                        return io.smallrye.mutiny.Uni.createFrom().item(
                                new SegmentInfo(timestamp, "silence")
                        );
                    }

                    // Use segmentation service to slice the audio into an HLS segment
                    SoundFragment soundFragment = fragment.getSoundFragment();
                    String title = soundFragment.getTitle();
                    String id = soundFragment.getId();

                    // Create a timestamped segment
                    return segmentationService.createSegment(
                                    soundFragment.getFilePath(),
                                    soundFragment.getMetadata(),
                                    id,
                                    timestamp,
                                    config.getSegmentDuration()
                            )
                            .onItem().transform(segment -> {
                                // Add the segment to our playlist
                                addSegment(segment);

                                // Mark as played in scheduler
                                playlistScheduler.trackPlayedFragment(stationId, fragment);

                                return new SegmentInfo(timestamp, title);
                            });
                });
    }

    /**
     * Setup maintenance tasks for playlist health
     */
    private void setupMaintenanceTasks() {
        // Schedule a task to clean up old segments
        maintenanceSubscription = Multi.createFrom().ticks().every(java.time.Duration.ofMinutes(1))
                .subscribe().with(
                        tick -> cleanupOldSegments(),
                        error -> LOGGER.error("Error in maintenance task", error)
                );
    }

    /**
     * Clean up segments that are no longer needed in the window
     */
    private void cleanupOldSegments() {
        if (segments.size() <= config.getSegmentWindow()) {
            return; // Not enough segments to clean up
        }

        // Get the oldest timestamp we want to keep
        long latestTimestamp = segments.lastKey();
        long oldestToKeep = latestTimestamp - (config.getSegmentWindow() * config.getSegmentDuration());

        // Remove segments older than the window
        segments.headMap(oldestToKeep).clear();

        // Update metadata
        segmentTitles.entrySet().removeIf(entry ->
                Long.parseLong(entry.getKey()) < oldestToKeep
        );

        LOGGER.debug("Cleaned up old segments. Remaining: {}", segments.size());
    }

    /**
     * Create an empty playlist when no segments are available
     */
    private String createEmptyPlaylist() {
        return "#EXTM3U\n" +
                "#EXT-X-VERSION:3\n" +
                "#EXT-X-ALLOW-CACHE:NO\n" +
                "#EXT-X-TARGETDURATION:" + config.getSegmentDuration() + "\n" +
                "#EXT-X-MEDIA-SEQUENCE:" + mediaSequence.get() + "\n";
    }

    /**
     * Shutdown the playlist and clean up resources
     */
    @PreDestroy
    public void shutdown() {
        LOGGER.info("Shutting down reactive HLS playlist for station: {}", stationId);

        // Cancel subscriptions
        if (segmentGeneratorSubscription != null) {
            segmentGeneratorSubscription.cancel();
        }

        if (maintenanceSubscription != null) {
            maintenanceSubscription.cancel();
        }

        // Unregister with scheduler
        playlistScheduler.unregisterPlaylist(stationId);

        // Clear data
        segments.clear();
        segmentTitles.clear();

        synchronized (recentlyPlayedTitles) {
            recentlyPlayedTitles.clear();
        }

        LOGGER.info("Reactive HLS playlist for station {} has been shut down", stationId);
    }

    /**
     * Helper class to track segment info
     */
    private static class SegmentInfo {
        final long timestamp;
        final String title;

        SegmentInfo(long timestamp, String title) {
            this.timestamp = timestamp;
            this.title = title;
        }
    }
}