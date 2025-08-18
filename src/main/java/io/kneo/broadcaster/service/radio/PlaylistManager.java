package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.config.BroadcasterConfig;
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
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlaylistManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistManager.class);
    private static final int SELF_MANAGING_INTERVAL_SECONDS = 300;
    private static final int READY_QUEUE_MAX_SIZE = 2;
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
    private final String tempBaseDir;

    public PlaylistManager(BroadcasterConfig broadcasterConfig, IStreamManager playlist) {
        this.soundFragmentService = playlist.getSoundFragmentService();
        this.segmentationService = playlist.getSegmentationService();
        this.radioStation = playlist.getRadioStation();
        this.brand = radioStation.getSlugName();
        this.tempBaseDir = broadcasterConfig.getPathUploads() + "/playlist-processing";
        LOGGER.info("Created PlaylistManager for brand: {}", brand);
    }

    public void startSelfManaging() {
       /* scheduler.scheduleAtFixedRate(() -> {
            try {
                if (regularQueue.size() <= TRIGGER_SELF_MANAGING) {
                    addFragments();
                } else {
                    LOGGER.debug("Skipping fragment addition - ready queue is full ({} items)",
                            READY_QUEUE_MAX_SIZE);
                }
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, SELF_MANAGING_INTERVAL_SECONDS, TimeUnit.SECONDS);*/
    }

    private void addFragments() {
        LOGGER.info("Adding {} fragments for brand {}", 2, brand);

        soundFragmentService.getForBrand(brand, 2, true, SuperUser.build())
                .onItem().transformToMulti(fragments -> Multi.createFrom().iterable(fragments))
                .onItem().call(fragment -> {
                    try {
                        List<FileMetadata> metadataList = fragment.getSoundFragment().getFileMetadataList();
                        FileMetadata metadata = metadataList.get(0);

                        return soundFragmentService.getFileBySlugName(
                                        fragment.getSoundFragment().getId(),
                                        metadata.getSlugName(),
                                        SuperUser.build()
                                )
                                .chain(fetchedMetadata -> fetchedMetadata.materializeFileStream(tempBaseDir)
                                        .onItem().transform(tempFilePath -> fetchedMetadata))
                                .chain(materializedMetadata -> addFragmentToSlice(fragment, materializedMetadata, radioStation.getBitRate()));
                    } catch (Exception e) {
                        LOGGER.warn("Skipping fragment due to metadata error: {}", e.getMessage());
                        return Uni.createFrom().item(false);
                    }
                })
                .collect().asList()
                .subscribe().with(
                        processedItems -> LOGGER.info("Successfully processed and added {} fragments for brand {}.", processedItems.size(), brand),
                        error -> {
                            LOGGER.error("Error during the processing of fragments for brand {}: {}", brand, error.getMessage(), error);
                            radioStation.setStatus(RadioStationStatus.SYSTEM_ERROR);
                        }
                );
    }

    public Uni<Boolean> addFragmentToSlice(BrandSoundFragment brandSoundFragment, long bitRate) {
        try {
            List<FileMetadata> metadataList = brandSoundFragment.getSoundFragment().getFileMetadataList();
            FileMetadata metadata = metadataList.get(0);
            LOGGER.debug("Found pre-populated stream data. Slicing directly.");
            return this.addFragmentToSlice(brandSoundFragment, metadata, bitRate);
        } catch (Exception e) {
            LOGGER.warn("Skipping fragment due to metadata error: {}", e.getMessage());
            return Uni.createFrom().item(false);
        }
    }

    public Uni<Boolean> addFragmentToSlice(BrandSoundFragment brandSoundFragment, FileMetadata fileMetadata, long bitRate) {
        return segmentationService.slice(brandSoundFragment.getSoundFragment(), fileMetadata.getTemporaryFilePath(), bitRate)
                .onItem().transformToUni(segments -> {
                    if (segments.isEmpty()) {
                        LOGGER.warn("Slicing from metadata {} resulted in zero segments.", fileMetadata.getFileKey());
                        return Uni.createFrom().item(false);
                    }

                    brandSoundFragment.setSegments(segments);

                    readyFragmentsLock.writeLock().lock();
                    try {
                        boolean isAiDjSubmit = brandSoundFragment.getQueueNum() == 10;

                        if (isAiDjSubmit) {
                            prioritizedQueue.add(brandSoundFragment);
                            LOGGER.info("Added AI submit fragment for brand {}: {}", brand, fileMetadata.getFileOriginalName());
                            if (prioritizedQueue.size() >= READY_QUEUE_MAX_SIZE) {
                                radioStation.setStatus(RadioStationStatus.QUEUE_SATURATED);
                                LOGGER.warn("Queue is full for brand {}. AI DJ should stop generating new songs.", brand);
                            }
                        } else {
                            regularQueue.add(brandSoundFragment);
                            LOGGER.info("Added and sliced fragment from metadata for brand {}: {}", brand, fileMetadata.getFileOriginalName());
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
                BrandSoundFragment nextFragment = prioritizedQueue.poll();
                if (prioritizedQueue.size() < READY_QUEUE_MAX_SIZE &&
                        radioStation.getStatus() == RadioStationStatus.QUEUE_SATURATED) {
                    radioStation.setStatus(RadioStationStatus.ON_LINE);
                    LOGGER.info("Queue capacity available - switching back to ON_LINE");
                }
                moveFragmentToProcessedList(nextFragment);
                return nextFragment;
            }

            if (!regularQueue.isEmpty()) {
                BrandSoundFragment nextFragment = regularQueue.poll();
                moveFragmentToProcessedList(nextFragment);
                return nextFragment;
            }

            // Starvation case
            addFragments();
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
}