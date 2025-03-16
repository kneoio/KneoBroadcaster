package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.PlaylistItem;
import io.kneo.broadcaster.model.PlaylistItemSong;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.stats.PlaylistStats;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.radio.PlaylistScheduler;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class HLSPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private final ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final AtomicLong lastRequestedSegment = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AtomicReference<String> lastRequestedFragmentName = new AtomicReference<>("");

    private final ConcurrentLinkedQueue<PlaylistRange> mainQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PlaylistItem> interstitialQueue = new ConcurrentLinkedQueue<>();

    @Getter
    @Setter
    private String brandName;

    private final AudioSegmentationService segmentationService;

    private final SoundFragmentService soundFragmentService;

    private final PlaylistScheduler playlistScheduler;

    private final HlsPlaylistConfig config;

    @Inject
    public HLSPlaylist(
            HlsPlaylistConfig config,
            BroadcasterConfig broadcasterConfig,
            SoundFragmentService soundFragmentService,
            String brandName,
            PlaylistScheduler playlistScheduler) {
        this.config = config;
        this.brandName = brandName;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = new AudioSegmentationService(broadcasterConfig);
        this.playlistScheduler = playlistScheduler;
    }

    public void initialize() {
        LOGGER.info("New broadcast initialized for {}, sequence: {}", brandName, currentSequence.get());
        playlistScheduler.registerPlaylist(this);
        queueKeeper();
        janitor();
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
                    .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                    .append("#EXT-X-MEDIA-SEQUENCE:").append(range.start()).append("\n");

            Map<Long, HlsSegment> rangeSegments = segments.subMap(range.start(), true, range.end(), true);

            rangeSegments.values()
                    .forEach(segment -> {
                        String songName = segment.getSongName();
                        playlist.append("#EXTINF:")
                                .append(segment.getDuration())
                                .append(",")
                                .append(songName)
                                .append("\n")
                                .append("segments/")
                                .append(segment.getSequenceNumber())
                                .append(".ts\n");
                    });
            return playlist.toString();
        } else {
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n#EXT-X-TARGETDURATION:" +
                    config.getSegmentDuration() + "\n#EXT-X-MEDIA-SEQUENCE:" + currentSequence.get() +
                    "\n#EXT-X-DISCONTINUITY-SEQUENCE:" + (currentSequence.get() / 1000) + "\n";
        }
    }

    public HlsSegment getSegment(long sequence) {
        HlsSegment segment = segments.get(sequence);
        lastRequestedSegment.set(segment.getSequenceNumber());
        lastRequestedFragmentName.set(segment.getSongName());
        return segment;
    }

    public void addSegment(HlsSegment segment) {
        if (segment == null) {
            LOGGER.warn("Attempted to add null segment");
            return;
        }

        segments.put(segment.getSequenceNumber(), segment);
        totalBytesProcessed.addAndGet(segment.getSize());
    }

    public void addRangeToQueue(PlaylistRange range) {
        mainQueue.offer(range);
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public long getCurrentSequenceAndIncrement() {
        return currentSequence.getAndIncrement();
    }

    public long getCurrentSequence() {
        return currentSequence.get();
    }

    public long getLastSegmentKey() {
        ConcurrentNavigableMap<Long, HlsSegment> map = segments;
        return map.isEmpty() ? 0L : map.lastKey();
    }

    public long getLastRequestedSegment() {
        return lastRequestedSegment.get();
    }

    public String getLastRequestedFragmentName() {
        return lastRequestedFragmentName.get();
    }

    public PlaylistStats getStats() {
        // Get recently played titles from the playlist scheduler
        List<String> recentlyPlayedTitles = playlistScheduler.getRecentlyPlayedTitles(brandName, 10);
        return PlaylistStats.fromPlaylist(this, recentlyPlayedTitles);
    }

    public int getQueueSize() {
        return mainQueue.size();
    }

    public long getTotalBytesProcessed() {
        return totalBytesProcessed.get();
    }

    private void queueKeeper() {
        LOGGER.info("Starting maintenance for: {}", brandName);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (segments.size() < config.getMinSegments()) {
                    LOGGER.info("Playlist for {} needs more fragments, current: {}",
                            brandName, getSegmentCount());

                    soundFragmentService.getForBrand(brandName, 5, true)
                            .subscribe().with(
                                    fragments -> {
                                        for (BrandSoundFragment fragment : fragments) {
                                            SoundFragment soundFragment = fragment.getSoundFragment();
                                            if (!playlistScheduler.isRecentlyPlayed(brandName, fragment)) {
                                                PlaylistItem item = new PlaylistItemSong(soundFragment);
                                                segmentationService.sliceAndAdd(
                                                        this,
                                                        item.getFilePath(),
                                                        item.getMetadata(),
                                                        item.getId()
                                                );
                                                // Track in playlist scheduler instead of local set
                                                playlistScheduler.trackPlayedFragment(brandName, fragment);
                                            } else {
                                                LOGGER.warn("Fragment already played: id={}, title={}",
                                                        fragment.getId(),
                                                        soundFragment.getTitle());
                                            }
                                        }
                                    },
                                    error -> LOGGER.error("Error fetching fragments: {}", error.getMessage(), error)
                            );
                } else {
                    LOGGER.warn("Still enough segments: count={}", segments.size());

                }
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, 3, TimeUnit.MINUTES);
    }

    private void janitor() {
        LOGGER.info("Starting janitor for playlist: {}", brandName);
        int initialSize = segments.size();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (segments.size() > config.getMinSegments()) {
                    int segmentsToDelete = 50;
                    Long oldestToKeep = segments.navigableKeySet().stream()
                            .skip(segmentsToDelete)
                            .findFirst()
                            .orElse(null);

                    if (oldestToKeep != null) {
                        segments.headMap(oldestToKeep).clear();
                        LOGGER.warn("It was {}, after squeezing {}", initialSize, segments.size());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error during cleaning: {}", e.getMessage(), e);
            }
        }, 3600, 240, TimeUnit.SECONDS);
    }

    public void shutdown() {
        LOGGER.info("Shutting down playlist maintenance for: {}", brandName);
        playlistScheduler.unregisterPlaylist(brandName);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            segments.clear();
            currentSequence.set(0);
            totalBytesProcessed.set(0);
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}