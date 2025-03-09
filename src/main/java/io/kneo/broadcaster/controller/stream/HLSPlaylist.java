package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.exceptions.PlaylistException;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class HLSPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private final ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicInteger segmentsCreated = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // Track the last played sequence
    private final AtomicLong lastPlayedSequence = new AtomicLong(-1);

    @Getter
    @Setter
    private String brandName;

    @Getter
    private Slide currentSlide;

    @Inject
    private final AudioSegmentationService segmentationService;

    @Inject
    private final SoundFragmentService soundFragmentService;

    private final HlsPlaylistConfig config;

    public HLSPlaylist(HlsPlaylistConfig config, SoundFragmentService soundFragmentService) {
        this.config = config;
        this.soundFragmentService = soundFragmentService;
        segmentationService = new AudioSegmentationService();
    }

    // Initialize playlist for a specific brand
    public Uni<HLSPlaylist> initialize(String brandName) {
        this.brandName = brandName;

        try {
            return soundFragmentService.getForBrand(brandName)
                    .onItem().transform(fragments -> {
                        for (BrandSoundFragment fragment : fragments) {
                            try {
                                if (fragment.getSoundFragment().getFilePath() != null) {
                                    addFragment(fragment);
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        if (getSegmentCount() == 0) {
                            throw new PlaylistException("Playlist is still empty after init");
                        }

                        // Start self-maintenance
                        startSelfMaintenance();

                        return this;
                    });
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
    }

    // Schedule self-maintenance to keep segments available
    private void startSelfMaintenance() {
        LOGGER.info("Starting self-maintenance for playlist: {}", brandName);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (getSegmentCount() < config.getMinSegments()) {
                    LOGGER.info("Playlist for {} needs more fragments, current: {}",
                            brandName, getSegmentCount());

                    soundFragmentService.getForBrand(brandName)
                            .subscribe().with(
                                    fragments -> {
                                        if (!fragments.isEmpty()) {
                                            try {
                                                addFragment(fragments.get(0));
                                            } catch (IOException e) {
                                                LOGGER.error("Error during playlist self-maintenance: {}", e.getMessage(), e);
                                            }
                                        } else {
                                            LOGGER.warn("No fragments available for {}", brandName);
                                        }
                                    },
                                    error -> LOGGER.error("Error fetching fragments: {}", error.getMessage(), error)
                            );
                } else {
                    LOGGER.debug("Playlist for {} has sufficient fragments: {}",
                            brandName, getSegmentCount());
                }
            } catch (Exception e) {
                LOGGER.error("Error during playlist self-maintenance for {}: {}", brandName, e.getMessage(), e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    // Shutdown method to clean up resources
    public void shutdown() {
        LOGGER.info("Shutting down playlist maintenance for: {}", brandName);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public String generatePlaylist() {
        synchronized (segments) {
            if (segments.isEmpty()) {
                LOGGER.warn("Attempted to generate playlist with no segments");
                return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n#EXT-X-TARGETDURATION:" + config.getSegmentDuration() + "\n#EXT-X-MEDIA-SEQUENCE:0\n";
            }
            long latestSequence = segments.lastKey();
            int slidingWindowSize = config.getSlidingWindowSize();
            long oldestSequence = Math.max(0, latestSequence - slidingWindowSize + 1);

            StringBuilder playlist = new StringBuilder(slidingWindowSize * 100);
            playlist.append("#EXTM3U\n")
                    .append("#EXT-X-VERSION:3\n")
                    .append("#EXT-X-ALLOW-CACHE:NO\n")
                    .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                    .append("#EXT-X-MEDIA-SEQUENCE:").append(oldestSequence).append("\n");

            List<String> songNames = new ArrayList<>();

            segments.tailMap(oldestSequence).values().stream()
                    .limit(slidingWindowSize)
                    .forEach(segment -> {
                        String songName = segment.getSongName();
                        songNames.add(songName);
                        playlist.append("#EXTINF:")
                                .append(segment.getDuration())
                                .append(",")
                                .append(songName)
                                .append("\n")
                                .append("segments/")
                                .append(segment.getSequenceNumber())
                                .append(".ts\n");
                    });

            List<String> uniqueSongNames = songNames.stream()
                    .distinct()
                    .collect(Collectors.toList());

            currentSlide = new Slide(
                    oldestSequence,
                    oldestSequence + slidingWindowSize - 1,
                    uniqueSongNames
            );

            return playlist.toString();
        }
    }

    public void addSegment(HlsSegment segment) {
        if (segment == null) {
            LOGGER.warn("Attempted to add null segment");
            return;
        }
        cleanupSegmentsIfNeeded();

        segments.put(segment.getSequenceNumber(), segment);
        segmentsCreated.incrementAndGet();
        totalBytesProcessed.addAndGet(segment.getSize());
    }

    public HlsSegment getSegment(long sequence) {
        // Update the last played sequence
        lastPlayedSequence.set(sequence);
        return segments.get(sequence);
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public long getCurrentSequence() {
        return currentSequence.getAndIncrement();
    }

    public long getCurrentSequenceWithoutIncrementing() {
        return currentSequence.get();
    }

    public Long getFirstSegmentKey() {
        return segments.isEmpty() ? null : segments.firstKey();
    }

    public Long getLastSegmentKey() {
        return segments.isEmpty() ? null : segments.lastKey();
    }

    private void addFragment(BrandSoundFragment fragment) throws IOException {
        if (fragment == null) {
            LOGGER.warn("Attempted to add empty segment");
            return;
        }

        String songMetadata = String.format("%s - %s",
                fragment.getSoundFragment().getArtist(),
                fragment.getSoundFragment().getTitle());

        segmentationService.sliceAndAdd(
                this,
                fragment.getSoundFragment().getFilePath(),
                songMetadata,
                fragment.getId()
        );
    }

    private void cleanupSegmentsIfNeeded() {
        if (segments.size() > config.getMinSegments()) {
            long lastPlayed = getLastPlayedSequence();
            if (lastPlayed == -1) {
                // No segments have been played yet, so don't delete anything
                return;
            }

            // Calculate the oldest segment to keep (leave a buffer of 2 segments)
            long oldestToKeep = lastPlayed - 2; // Adjust buffer size as needed

            // Clear segments older than oldestToKeep
            segments.headMap(oldestToKeep).clear();
        }
    }

    private long getLastPlayedSequence() {
        return lastPlayedSequence.get();
    }
}