package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final int SELF_MANAGING_INTERVAL_SECONDS = 300;
    private static final int READY_QUEUE_MAX_SIZE = 3;
    private static final int READY_REGULAR_QUEUE_MAX_SIZE = 6;
    private static final int TRIGGER_SELF_MANAGING = 2;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 3;

    private final ReadWriteLock readyFragmentsLock = new ReentrantReadWriteLock();
    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();

    @Getter
    private final LinkedList<BrandSoundFragment> obtainedByHlsPlaylist = new LinkedList<>();

    @Getter
    private final PriorityQueue<BrandSoundFragment> regularQueue = new PriorityQueue<>(Comparator.comparing(BrandSoundFragment::getQueueNum));

    @Getter
    private final LinkedList<BrandSoundFragment> prioritizedQueue = new LinkedList<>();

    @Getter
    private final String brand;

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

    }

    public void startSelfManaging() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (regularQueue.size() <= TRIGGER_SELF_MANAGING) {
                    addFragments(2);
                } else {
                    LOGGER.debug("Skipping fragment addition - ready queue is full ({} items)",
                            READY_QUEUE_MAX_SIZE);
                }
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, SELF_MANAGING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }


    public Uni<Boolean> addFragmentToSlice(BrandSoundFragment brandSoundFragment) {
        try {
            var metadataList = brandSoundFragment.getSoundFragment().getFileMetadataList();
            if (metadataList == null || metadataList.isEmpty()) {
                LOGGER.warn("Skipping fragment with empty metadata list: {}",
                        brandSoundFragment.getSoundFragment().getMetadata());
                return Uni.createFrom().item(false);
            }

            FileMetadata metadata = metadataList.get(0);

            // Check if we already have stream data
            if (metadata.getInputStream() != null) {
                LOGGER.debug("Found pre-populated stream data. Slicing directly.");
                return this.addFragmentToSlice(brandSoundFragment, metadata);
            }

            // Otherwise fetch the file metadata with stream
            return soundFragmentService.getFile(
                            brandSoundFragment.getSoundFragment().getId(),
                            metadata.getSlugName(),
                            SuperUser.build()
                    )
                    .onItem().transformToUni(fetchedMetadata ->
                            this.addFragmentToSlice(brandSoundFragment, fetchedMetadata)
                    );

        } catch (Exception e) {
            LOGGER.warn("Skipping fragment due to metadata error: {}", e.getMessage());
            return Uni.createFrom().item(false);
        }
    }

    public Uni<Boolean> addFragmentToSlice(BrandSoundFragment brandSoundFragment, FileMetadata fileMetadata) {
        return segmentationService.slice(brandSoundFragment.getSoundFragment(), fileMetadata)
                .onItem().transformToUni(segments -> {
                    if (segments.isEmpty()) {
                        LOGGER.warn("Slicing from metadata {} resulted in zero segments.", fileMetadata.getFileKey());
                        return Uni.createFrom().item(false);
                    }

                    brandSoundFragment.setSegments(segments);

                    readyFragmentsLock.writeLock().lock();
                    try {
                        boolean isAiDjSubmit = brandSoundFragment.getQueueNum() == 10;
                        int totalQueueSize = prioritizedQueue.size() + regularQueue.size();

                        if (isAiDjSubmit) {
                            if (totalQueueSize >= READY_QUEUE_MAX_SIZE) {
                                radioStation.setStatus(RadioStationStatus.QUEUE_SATURATED);
                            }
                            prioritizedQueue.add(brandSoundFragment);
                            LOGGER.info("Added AI submit fragment for brand {}: {}", brand, fileMetadata.getFileOriginalName());
                        } else {
                            if (totalQueueSize >= READY_QUEUE_MAX_SIZE) {
                                radioStation.setStatus(RadioStationStatus.QUEUE_SATURATED);
                                return Uni.createFrom().item(false);
                            } else {
                                regularQueue.add(brandSoundFragment);
                                LOGGER.info("Added and sliced fragment from metadata for brand {}: {}", brand, fileMetadata.getFileOriginalName());
                            }
                        }
                        return Uni.createFrom().item(true);
                    } finally {
                        readyFragmentsLock.writeLock().unlock();
                    }
                });
    }

    public BrandSoundFragment getNextFragment() {
        readyFragmentsLock.writeLock().lock();
        try {
            if (!prioritizedQueue.isEmpty()) {
                if (prioritizedQueue.size() < READY_QUEUE_MAX_SIZE &&
                        radioStation.getStatus() == RadioStationStatus.QUEUE_SATURATED) {
                    radioStation.setStatus(RadioStationStatus.ON_LINE);
                    LOGGER.info("Queue capacity available - switching back to ON_LINE (case 1)");
                }
                BrandSoundFragment nextFragment = prioritizedQueue.poll();
                moveFragmentToProcessedList(nextFragment);
                return nextFragment;
            }

            if (!regularQueue.isEmpty()) {
                BrandSoundFragment nextFragment = regularQueue.poll();
                moveFragmentToProcessedList(nextFragment);

                if (regularQueue.size() < READY_REGULAR_QUEUE_MAX_SIZE &&
                        radioStation.getStatus() == RadioStationStatus.QUEUE_SATURATED) {
                    radioStation.setStatus(RadioStationStatus.ON_LINE);
                    LOGGER.info("Queue capacity available - switching back to ON_LINE (case 2)");
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
                        error -> {
                            LOGGER.error("Error during the processing of fragments for brand {}: {}", brand, error.getMessage(), error);
                            radioStation.setStatus(RadioStationStatus.SYSTEM_ERROR);
                        }
                );
    }
}