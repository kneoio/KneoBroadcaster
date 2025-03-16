package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.PlaylistItem;
import io.kneo.broadcaster.model.PlaylistItemSong;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class HLSPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private final ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Set<BrandSoundFragment> recentlyPlayed = ConcurrentHashMap.newKeySet();

    private final ConcurrentLinkedQueue<PlaylistRange> mainQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PlaylistItem> interstitialQueue = new ConcurrentLinkedQueue<>();

    @Getter
    @Setter
    private String brandName;

    @Inject
    private final AudioSegmentationService segmentationService;

    @Inject
    private final SoundFragmentService soundFragmentService;

    private final HlsPlaylistConfig config;

    @Inject
    public HLSPlaylist(
            HlsPlaylistConfig config,
            BroadcasterConfig broadcasterConfig,
            SoundFragmentService soundFragmentService,
            String brandName) {
        this.config = config;
        this.brandName = brandName;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = new AudioSegmentationService(broadcasterConfig);
    }

    public void initialize() {
        LOGGER.info("New broadcast initialized for {}, sequence: {}", brandName, currentSequence.get());
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
        return segments.get(sequence);
    }

    public void addSegment(HlsSegment segment) {
        if (segment == null) {
            LOGGER.warn("Attempted to add null segment");
            return;
        }

        segments.put(segment.getSequenceNumber(), segment);
        totalBytesProcessed.addAndGet(segment.getSize());
    }

    // Add this method to HLSPlaylist
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

    public Long getFirstSegmentKey() {
        return segments.isEmpty() ? null : segments.firstKey();
    }

    public Long getLastSegmentKey() {
        return segments.isEmpty() ? null : segments.lastKey();
    }

    private void queueKeeper() {
        LOGGER.info("Starting maintenance for: {}", brandName);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (segments.size() < config.getMinSegments()) {
                    LOGGER.info("Playlist for {} needs more fragments, current: {}",
                            brandName, getSegmentCount());

                    soundFragmentService.getForBrand(brandName, 5)
                            .subscribe().with(
                                    fragments -> {
                                        for (BrandSoundFragment fragment : fragments) {
                                            SoundFragment soundFragment = fragment.getSoundFragment();
                                            if (!recentlyPlayed.contains(fragment)) {
                                                PlaylistItem item = new PlaylistItemSong(soundFragment);
                                                segmentationService.sliceAndAdd(
                                                        this,
                                                        item.getFilePath(),
                                                        item.getMetadata(),
                                                        item.getId()
                                                );
                                                recentlyPlayed.add(fragment);
                                            } else {
                                                LOGGER.warn("Fragment already played: id={}, title={}",
                                                        fragment.getId(),
                                                        soundFragment.getTitle());
                                            }
                                        }
                                    },
                                    error -> LOGGER.error("Error fetching fragments: {}", error.getMessage(), error)
                            );
                }
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, 60, TimeUnit.SECONDS);
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
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            segments.clear();;
            currentSequence.set(0);
            totalBytesProcessed.set(0);
            recentlyPlayed.clear();

        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}