package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlaylistManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistManager.class);
    private static final String SCHEDULED_TASK_ID = "playlist-manager-task";
    private static final int INTERVAL_SECONDS = 60;

    @Getter
    private final LinkedList<BrandSoundFragment> playedFragmentsList = new LinkedList<>();

    @Getter
    private final LinkedList<BrandSoundFragment> readyToPlayList = new LinkedList<>();

    @Getter
    private final String brand;

    @Getter
    private final SchedulerTaskTimeline taskTimeline = new SchedulerTaskTimeline();

    private final SoundFragmentService soundFragmentService;

    private final Map<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private final ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();


    private final AudioSegmentationService segmentationService;

    @Getter
    @Setter
    private BrandSoundFragment currentlyPlaying;

    public PlaylistManager(SoundFragmentService soundFragmentService, AudioSegmentationService segmentationService, String brand) {
        LOGGER.info("Created PlaylistManager for brand: {}", brand);
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = segmentationService;
        this.brand = brand;
        taskTimeline.registerTask(
                SCHEDULED_TASK_ID,
                "Playlist Manager",
                INTERVAL_SECONDS
        );
    }

    public void start() {
        LOGGER.info("Starting PlaylistManager for brand: {}", brand);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                taskTimeline.updateProgress();
                processFragments();
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void processFragments() {
        AtomicBoolean isProcessing = processingFlags.computeIfAbsent(brand, k -> new AtomicBoolean(false));
        if (isProcessing.get() || !isProcessing.compareAndSet(false, true)) {
            return;
        }

        try {
            if (readyToPlayList.size() < 10) {
                requestMoreFragments();
            }
            isProcessing.set(false);
        } catch (Exception e) {
            LOGGER.error("Error processing timer tick for brand {}: {}", brand, e.getMessage(), e);
            isProcessing.set(false);
        }
    }

    private void requestMoreFragments() {
        int fragmentsToRequest = determineFragmentsToRequest(segments.size());
        LOGGER.info("Adding {} fragments for brand {} ", fragmentsToRequest, brand);

        soundFragmentService.getForBrand(brand, fragmentsToRequest, true)
                .subscribe().with(
                        fragments -> {
                            if (!fragments.isEmpty()) {
                                addFragmentsToReadyList(fragments);
                            }
                            processingFlags.get(brand).set(false);
                        },
                        error -> {
                            LOGGER.error("Error fetching fragments for brand {}: {}",
                                    brand, error.getMessage(), error);
                            processingFlags.get(brand).set(false);
                        }
                );
    }

    private static int determineFragmentsToRequest(int size) {
        if (size < 1) return 5;
        return 10;
    }

    private void addFragmentsToReadyList(List<BrandSoundFragment> fragments) {
        for (BrandSoundFragment brandSoundFragment : fragments) {
            if (isNewFragment(brandSoundFragment)) {
                brandSoundFragment.setSegments(segmentationService.slice(brandSoundFragment.getSoundFragment()));
                readyToPlayList.add(brandSoundFragment);
            }
        }
        LOGGER.info("Added {} fragments to ready list for brand {}", fragments.size(), brand);
    }

    public boolean isNewFragment(BrandSoundFragment fragment) {
        for (BrandSoundFragment played : playedFragmentsList) {
            if (played.getSoundFragment().getId().equals(fragment.getSoundFragment().getId())) {
                return false;
            }
        }

        for (BrandSoundFragment ready : readyToPlayList) {
            if (ready.getSoundFragment().getId().equals(fragment.getSoundFragment().getId())) {
                return false;
            }
        }

        if (currentlyPlaying != null &&
                currentlyPlaying.getSoundFragment().getId().equals(fragment.getSoundFragment().getId())) {
            return false;
        }

        return true;
    }

    public void moveFragmentToPlayed(UUID fragmentId) {
        // Use removeIf to find and move the fragment
        boolean removed = readyToPlayList.removeIf(fragment ->
                fragment.getSoundFragment().getId().equals(fragmentId)
        );

        if (removed) {
            // Find the fragment that was removed
            BrandSoundFragment fragmentToMove = readyToPlayList.stream()
                    .filter(fragment -> fragment.getSoundFragment().getId().equals(fragmentId))
                    .findFirst()
                    .orElse(null);

            if (fragmentToMove != null) {
                playedFragmentsList.add(fragmentToMove); // Add to playedFragmentsList
                LOGGER.info("Fragment with UUID {} moved from readyToPlayList to playedFragmentsList for brand {}", fragmentId, brand);
                return;
            }
        }

        // If the fragment was not found, log a warning and return false
        LOGGER.warn("Fragment with UUID {} not found in readyToPlayList for brand {}", fragmentId, brand);
    }

    public void shutdown() {
        LOGGER.info("Shutting down PlaylistManager for brand: {}", brand);
        processingFlags.remove(brand);
        segments.clear();
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
}