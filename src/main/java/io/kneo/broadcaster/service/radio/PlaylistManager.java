package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.controller.stream.IStreamManager;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlaylistManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistManager.class);
    private static final String SCHEDULED_TASK_ID = "playlist-manager-task";
    private static final int INTERVAL_SECONDS = 240;
    private static final int READY_QUEUE_MAX_SIZE = 3;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 5;

    private final ReadWriteLock readyFragmentsLock = new ReentrantReadWriteLock();
    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();

    @Getter
    private final LinkedList<BrandSoundFragment> obtainedByHlsPlaylist = new LinkedList<>();

    @Getter
    private final PriorityQueue<BrandSoundFragment> segmentedAndReadyToBeConsumed = new PriorityQueue<>(Comparator.comparing(BrandSoundFragment::getQueueNum));

    @Getter
    private final String brand;

    @Getter
    private final SchedulerTaskTimeline taskTimeline = new SchedulerTaskTimeline();
    private final SoundFragmentService soundFragmentService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AudioSegmentationService segmentationService;
    private final RadioStation radioStation;

    public PlaylistManager(IStreamManager playlist) {
        this.soundFragmentService = playlist.getSoundFragmentService();
        this.segmentationService = playlist.getSegmentationService();
        this.radioStation = playlist.getRadioStation();
        this.brand = radioStation.getSlugName();
        LOGGER.info("Created PlaylistManager for brand: {}", brand);
        taskTimeline.registerTask(
                SCHEDULED_TASK_ID,
                "Playlist Manager",
                INTERVAL_SECONDS
        );
    }

    public void startSelfManaging() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (segmentedAndReadyToBeConsumed.size() < READY_QUEUE_MAX_SIZE) {
                    int neededFragments = READY_QUEUE_MAX_SIZE - segmentedAndReadyToBeConsumed.size();
                   // radioStation.setManagedBy(ManagedBy.ITSELF);
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

    public Uni<Boolean> addFragmentToSlice(BrandSoundFragment brandSoundFragment) {
        Path filePath = null;
        FileMetadata metadata = null;

        try {
            metadata = brandSoundFragment.getSoundFragment().getFileMetadataList().get(0);
            if (metadata != null) {
                filePath = metadata.getFilePath();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not defensively read file path from BrandSoundFragment, will attempt to fetch. Error: {}", e.getMessage());
        }

        if (filePath != null) {
            LOGGER.debug("Found pre-populated file path: {}. Slicing directly.", filePath);
            return this.addFragmentToSlice(brandSoundFragment, filePath);
        } else {
            LOGGER.debug("File path is null for sound fragment '{}', assuming lazy load and fetching.", brandSoundFragment.getSoundFragment().getMetadata());
            final FileMetadata finalMetadata = metadata;
            assert finalMetadata != null;
            Uni<Path> pathUni = soundFragmentService.getFile(
                            brandSoundFragment.getSoundFragment().getId(),
                            finalMetadata.getSlugName(),
                            SuperUser.build()
                    )
                    .onItem().transform(fetchedMetadata -> {
                        finalMetadata.setFilePath(fetchedMetadata.getFilePath());
                        return fetchedMetadata.getFilePath();
                    });

            return pathUni.onItem().transformToUni(resolvedPath ->
                    this.addFragmentToSlice(brandSoundFragment, resolvedPath)
            );
        }
    }

    public Uni<Boolean> addFragmentToSlice(BrandSoundFragment brandSoundFragment, Path filePath) {
        return segmentationService.slice(brandSoundFragment.getSoundFragment(), filePath)
                .onItem().transformToUni(segments -> {
                    if (segments.isEmpty()) {
                        LOGGER.warn("Slicing from path {} resulted in zero segments.", filePath);
                        return Uni.createFrom().item(false);
                    }

                    brandSoundFragment.setSegments(segments);

                    readyFragmentsLock.writeLock().lock();
                    try {
                        if (segmentedAndReadyToBeConsumed.size() >= READY_QUEUE_MAX_SIZE) {
                            LOGGER.debug("Cannot add fragment from path - ready queue is full ({} items)",
                                    READY_QUEUE_MAX_SIZE);
                            return Uni.createFrom().item(false);
                        } else {
                            radioStation.setStatus(RadioStationStatus.ON_LINE_WELL);
                        }

                        segmentedAndReadyToBeConsumed.add(brandSoundFragment);

                        LOGGER.info("Added and sliced fragment from path for brand {}: {}",
                                brand, filePath.getFileName().toString());
                        return Uni.createFrom().item(true);
                    } finally {
                        readyFragmentsLock.writeLock().unlock();
                    }
                })
                .onFailure().recoverWithItem(throwable -> {
                    LOGGER.error("Failed to add fragment to slice from path {}", filePath, throwable);
                    return false;
                });
    }

    public BrandSoundFragment getNextFragment() {
        readyFragmentsLock.writeLock().lock();
        try {
            if (!segmentedAndReadyToBeConsumed.isEmpty()) {
                BrandSoundFragment nextFragment = segmentedAndReadyToBeConsumed.poll();
                moveFragmentToProcessedList(nextFragment);
                if (segmentedAndReadyToBeConsumed.size() < READY_QUEUE_MAX_SIZE) {
                    radioStation.setStatus(RadioStationStatus.ON_LINE);
                }
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
            return;
        }

        LOGGER.info("Adding {} fragments for brand {}", fragmentsToRequest, brand);

        soundFragmentService.getForBrand(brand, fragmentsToRequest, true, SuperUser.build())
                .onItem().transformToMulti(fragments -> Multi.createFrom().iterable(fragments))
                .onItem().call(this::addFragmentToSlice)
                .collect().asList()
                .subscribe().with(
                        processedItems -> LOGGER.info("Successfully processed and added {} fragments for brand {}.", processedItems.size(), brand),
                        error -> LOGGER.error("Error during the reactive processing of fragments for brand {}: {}", brand, error.getMessage(), error)
                );
    }
}