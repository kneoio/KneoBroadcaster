package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.SoundFragment;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HLSPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private final ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicInteger segmentsCreated = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

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
                                    addFragment(fragment.getSoundFragment());
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
                if (segments.size() < config.getMinSegments()) {
                    LOGGER.info("Playlist for {} needs more fragments, current: {}",
                            brandName, getSegmentCount());

                    soundFragmentService.getForBrand(brandName)
                            .subscribe().with(
                                    fragments -> {
                                        for (BrandSoundFragment f: fragments) {
                                            try {
                                                addFragment(f.getSoundFragment());
                                            } catch (IOException e) {
                                                LOGGER.error("Error during playlist self-maintenance: {}", e.getMessage(), e);
                                            }
                                        }
                                    },
                                    error -> LOGGER.error("Error fetching fragments: {}", error.getMessage(), error)
                            );
                } else {
                    if (segments.size() > config.getMinSegments()) {
                        int segmentsToDelete = 50;
                        Long oldestToKeep = segments.navigableKeySet().stream()
                                .skip(segmentsToDelete)
                                .findFirst()
                                .orElse(null);

                        if (oldestToKeep != null) {
                            segments.headMap(oldestToKeep).clear();
                            LOGGER.warn("Now after squeezing {}", segments.size());
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error during playlist self-maintenance for {}: {}", brandName, e.getMessage(), e);
            }
        }, 60, 60, TimeUnit.SECONDS);
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
            int slidingWindowSize = config.getSlidingWindowSize();
            long oldestSequence = Math.max(0, segments.lastKey() - slidingWindowSize + 1);

            StringBuilder playlist = new StringBuilder(slidingWindowSize * 100);
            playlist.append("#EXTM3U\n")
                    .append("#EXT-X-VERSION:3\n")
                    .append("#EXT-X-ALLOW-CACHE:NO\n")
                    .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                    .append("#EXT-X-MEDIA-SEQUENCE:").append(oldestSequence).append("\n");
            List<String> songNames = new ArrayList<>();
            AtomicLong lastSeq = new AtomicLong();

            segments.tailMap(oldestSequence).values().stream()
                    .limit(slidingWindowSize)
                    .forEach(segment -> {
                        lastSeq.set(segment.getSequenceNumber());
                        String songName = segment.getSongName();
                        songNames.add(songName);
                        playlist.append("#EXTINF:")
                                .append(segment.getDuration())
                                .append(",")
                                .append(songName)
                                .append("\n")
                                .append("segments/")
                                .append(lastSeq.get())
                                .append(".ts\n");
                    });

            List<String> uniqueSongNames = songNames.stream()
                    .distinct()
                    .toList();

            currentSlide = new Slide(
                    oldestSequence,
                    lastSeq.get(),
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


        segments.put(segment.getSequenceNumber(), segment);
        segmentsCreated.incrementAndGet();
        totalBytesProcessed.addAndGet(segment.getSize());
    }

    public HlsSegment getSegment(long sequence) {
        return segments.get(sequence);
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

    private void addFragment(SoundFragment fragment) throws IOException {
        if (fragment == null) {
            LOGGER.warn("Attempted to add empty segment");
            return;
        }

        String songMetadata = String.format("%s - %s",
                fragment.getArtist(),
                fragment.getTitle());

        segmentationService.sliceAndAdd(
                this,
                fragment.getFilePath(),
                songMetadata,
                fragment.getId()
        );
    }

    private long getLastPlayedSequence() {
        return lastPlayedSequence.get();
    }
}