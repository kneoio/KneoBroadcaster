package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlaylistManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistManager.class);
    private static final String SCHEDULED_TASK_ID = "playlist-manager-task";
    private static final int INTERVAL_SECONDS = 240;
    private static final int READY_QUEUE_MAX_SIZE = 10;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 10;

    private final ReadWriteLock readyFragmentsLock = new ReentrantReadWriteLock();
    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();

    @Getter
    private final LinkedList<BrandSoundFragment> obtainedByHlsPlaylist = new LinkedList<>();

    @Getter
    private final LinkedList<BrandSoundFragment> segmentedAndReadyToBeConsumed = new LinkedList<>();

    @Getter
    private final String brand;

    private final HlsPlaylistConfig config;

    @Getter
    private final SchedulerTaskTimeline taskTimeline = new SchedulerTaskTimeline();
    private final SoundFragmentService soundFragmentService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AudioSegmentationService segmentationService;

    public PlaylistManager(HLSPlaylist playlist) {
        this.config = playlist.getConfig();
        this.soundFragmentService = playlist.getSoundFragmentService();
        this.segmentationService = playlist.getSegmentationService();
        this.brand = playlist.getBrandName();
        LOGGER.info("Created PlaylistManager for brand: {}", brand);
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
                // Only add fragments if we have space in the ready queue
                if (segmentedAndReadyToBeConsumed.size() < READY_QUEUE_MAX_SIZE) {
                    int neededFragments = READY_QUEUE_MAX_SIZE - segmentedAndReadyToBeConsumed.size();
                    addFragments(neededFragments);
                } else {
                    LOGGER.debug("Skipping fragment addition - ready queue is full ({} items)",
                            READY_QUEUE_MAX_SIZE);
                }
                taskTimeline.updateProgress();
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean addFragmentToSlice(BrandSoundFragment brandSoundFragment) {
        readyFragmentsLock.writeLock().lock();
        try {
            // Don't add if queue is full
            if (segmentedAndReadyToBeConsumed.size() >= READY_QUEUE_MAX_SIZE) {
                LOGGER.debug("Cannot add fragment - ready queue is full ({} items)",
                        READY_QUEUE_MAX_SIZE);
                return false;
            }

            brandSoundFragment.setSegments(segmentationService.slice(brandSoundFragment.getSoundFragment()));
            segmentedAndReadyToBeConsumed.add(brandSoundFragment);

            LOGGER.info("Added and sliced fragment for brand {}: {}",
                    brand, brandSoundFragment.getSoundFragment().getMetadata());
            return true;
        } finally {
            readyFragmentsLock.writeLock().unlock();
        }
    }

    public BrandSoundFragment getNextFragment() {
        readyFragmentsLock.writeLock().lock();
        try {
            if (!segmentedAndReadyToBeConsumed.isEmpty()) {
                BrandSoundFragment nextFragment = segmentedAndReadyToBeConsumed.poll();
                moveFragmentToProcessedList(nextFragment);
                return nextFragment;
            }
            return null;
        } finally {
            readyFragmentsLock.writeLock().unlock();
        }
    }

    public PlaylistManagerStats getStats() {
        return PlaylistManagerStats.from(this);
    }

    private void moveFragmentToProcessedList(BrandSoundFragment fragmentToMove) {
        if (fragmentToMove != null) {
            slicedFragmentsLock.writeLock().lock();
            try {
                obtainedByHlsPlaylist.add(fragmentToMove);

                // Remove oldest fragment if queue size exceeds limit
                if (obtainedByHlsPlaylist.size() > PROCESSED_QUEUE_MAX_SIZE) {
                    BrandSoundFragment removed = obtainedByHlsPlaylist.poll();
                    LOGGER.debug("Removed oldest fragment from processed queue: {}",
                            removed.getSoundFragment().getMetadata());
                }
            } finally {
                slicedFragmentsLock.writeLock().unlock();
            }
        }
    }

    private void addFragments(int fragmentsToRequest) {
        if (fragmentsToRequest <= 0) {
            LOGGER.debug("No fragments needed to be added");
            return;
        }

        LOGGER.info("Adding {} fragments for brand {}", fragmentsToRequest, brand);
        soundFragmentService.getForBrand(brand, fragmentsToRequest, true)
                .subscribe().with(
                        fragments -> {
                            fragments.forEach(this::addFragmentToSlice);
                        },
                        error -> {
                            LOGGER.error("Error fetching fragments for brand {}: {}",
                                    brand, error.getMessage(), error);
                        }
                );
    }
}