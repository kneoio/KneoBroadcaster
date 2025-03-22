package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.controller.stream.PlaylistRange;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.PlaylistItem;
import io.kneo.broadcaster.model.PlaylistItemSong;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.stream.HlsTimerService;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class PlaylistMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistMaintenanceService.class);

    private static final String SCHEDULED_TASK_ID = "playlist-maintenance";
    private static final long INTERVAL_SECONDS = 240;

    private final Map<String, ScheduledFuture<?>> cleanupTasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();
    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Getter
    private final SchedulerTaskTimeline taskTimeline = new SchedulerTaskTimeline();

    @Inject
    private RadioStationPool radioStationPool;
    @Inject
    private SoundFragmentService soundFragmentService;
    @Inject
    private HlsPlaylistConfig hlsConfig;
    @Inject
    private PlaylistKeeper playlistKeeper;
    @Inject
    private HlsTimerService timerService;
    @Inject
    private SegmentsCleaner cleanupService;

    public void initializePlaylistMaintenance(HLSPlaylist playlist) {
        String brandName = playlist.getBrandName();
        LOGGER.info("Initializing maintenance for playlist: {}", brandName);
        playlistKeeper.registerPlaylist(playlist);
        taskTimeline.registerTask(
                SCHEDULED_TASK_ID,
                "Playlist Maintenance",
                INTERVAL_SECONDS
        );
        startTicker(brandName);
        startSegmentsCleaner(brandName);
    }

    private HLSPlaylist getPlaylist(String brandName) {
        try {
            return radioStationPool.get(brandName)
                    .map(station -> station != null ? (HLSPlaylist) station.getPlaylist() : null)
                    .await().indefinitely();
        } catch (Exception e) {
            LOGGER.error("Error getting playlist for brand {}: {}", brandName, e.getMessage(), e);
            return null;
        }
    }

    private void startTicker(String brandName) {
        LOGGER.info("Starting timer subscription for brand: {}", brandName);
        Cancellable subscription = timerService.getTicker().subscribe().with(
                timestamp -> processTimerTick(brandName, timestamp),
                error -> LOGGER.error("Timer subscription error for brand {}: {}", brandName, error.getMessage())
        );
        timerSubscriptions.put(brandName, subscription);
    }

    private void processTimerTick(String brandName, long timestamp) {
        HLSPlaylist playlist = getPlaylist(brandName);
        if (playlist == null) {
            return;
        }

        AtomicBoolean isProcessing = processingFlags.computeIfAbsent(brandName, k -> new AtomicBoolean(false));
        if (isProcessing.get() || !isProcessing.compareAndSet(false, true)) {
            return;
        }

        try {
            if (playlist.getSegmentCount() < hlsConfig.getMinSegments()) {
                int fragmentsToRequest = determineFragmentsToRequest(playlist);
                LOGGER.info("Adding {} fragments for brand {} ", fragmentsToRequest, brandName);

                soundFragmentService.getForBrand(brandName, fragmentsToRequest, true)
                        .subscribe().with(
                                fragments -> {
                                    if (!fragments.isEmpty()) {
                                        chopFragments(brandName, fragments, timestamp);
                                    } else {
                                        isProcessing.set(false);
                                    }
                                },
                                error -> {
                                    LOGGER.error("Error fetching fragments for brand {}: {}",
                                            brandName, error.getMessage(), error);
                                    isProcessing.set(false);
                                }
                        );
            } else {
                isProcessing.set(false);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing timer tick for brand {}: {}", brandName, e.getMessage(), e);
            isProcessing.set(false);
        }
    }

    private int determineFragmentsToRequest(HLSPlaylist playlist) {
        int count = playlist.getSegmentCount();
        if (count < 10) return 1;
        return 10;
    }

    private void chopFragments(String brandName, List<BrandSoundFragment> fragments, long timestamp) {
        HLSPlaylist playlist = getPlaylist(brandName);
        if (playlist == null) {
            processingFlags.get(brandName).set(false);
            return;
        }

        try {
            int addedCount = 0;

            for (BrandSoundFragment fragment : fragments) {
                SoundFragment soundFragment = fragment.getSoundFragment();

                if (playlistKeeper.notInPlaylist(brandName, fragment)) {
                    PlaylistItem item = new PlaylistItemSong(soundFragment);

                    if (playlist.getSegmentationService() != null) {
                        playlist.getSegmentationService().sliceAndAdd(
                                playlist,
                                item.getFilePath(),
                                item.getMetadata(),
                                item.getId()
                        );
                    } else {
                        addSegment(playlist, item, timestamp);
                    }

                    playlistKeeper.trackPlayedFragment(brandName, fragment);
                    addedCount++;
                }
            }

            if (addedCount > 0) {
                long currentSeq = playlist.getCurrentSequence();
                long start = Math.max(0, currentSeq - 10);
                playlist.addRangeToQueue(new PlaylistRange(start, currentSeq));

                LOGGER.info("Added {} segments to playlist for brand {}", addedCount, brandName);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing fragments for brand {}: {}", brandName, e.getMessage(), e);
        } finally {
            processingFlags.get(brandName).set(false);
        }
    }

    private void addSegment(HLSPlaylist playlist, PlaylistItem item, long timestamp) {
        try {
            Path filePath = item.getFilePath();
            byte[] data = Files.readAllBytes(filePath);

            long sequenceNumber = playlist.getCurrentSequenceAndIncrement();

            HlsSegment segment = new HlsSegment(
                    sequenceNumber,
                    data,
                    hlsConfig.getSegmentDuration(),
                    item.getId(),
                    item.getMetadata(),
                    timestamp
            );

            playlist.addSegment(segment);

            LOGGER.debug("Added segment to brand {}: seq={}, timestamp={}",
                    playlist.getBrandName(), sequenceNumber, timestamp);

        } catch (Exception e) {
            LOGGER.error("Error adding segment to brand {}: {}",
                    playlist.getBrandName(), e.getMessage(), e);
        }
    }

    private void startSegmentsCleaner(String brandName) {
        cleanupService.initializeTaskTimeline();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            HLSPlaylist playlist = getPlaylist(brandName);
            cleanupService.cleanupPlaylist(playlist);
        }, 60, SegmentsCleaner.INTERVAL_SECONDS, TimeUnit.SECONDS);

        cleanupTasks.put(brandName, task);
    }

    public void shutdownPlaylistMaintenance(String brandName) {
        LOGGER.info("Shutting down maintenance for brand: {}", brandName);
        playlistKeeper.unregisterPlaylist(brandName);
        processingFlags.remove(brandName);
    }

    @PreDestroy
    public void shutdown() {
        LOGGER.info("Shutting down playlist maintenance service");

        for (String brandName : timerSubscriptions.keySet()) {
            timerSubscriptions.get(brandName).cancel();
        }

        for (String brandName : cleanupTasks.keySet()) {
            cleanupTasks.get(brandName).cancel(false);
        }

        timerSubscriptions.clear();
        cleanupTasks.clear();
        processingFlags.clear();
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