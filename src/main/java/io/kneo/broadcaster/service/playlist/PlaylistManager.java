package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.live.SongMetadata;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.manipulation.segmentation.AudioSegmentationService;
import io.kneo.broadcaster.service.soundfragment.BrandSoundFragmentUpdateService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlaylistManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistManager.class);
    private static final int SELF_MANAGING_INTERVAL_SECONDS = 180;
    private static final int REGULAR_BUFFER_MAX = 2;
    private static final int READY_QUEUE_MAX_SIZE = 2;
    private static final int TRIGGER_SELF_MANAGING = 2;
    private static final int BACKPRESSURE_ON = 2;
    private static final long BACKPRESSURE_COOLDOWN_MILLIS = 90_000L;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 3;
    private static final long STARVING_FEED_COOLDOWN_MILLIS = 20_000L;

    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();

    @Getter
    private final LinkedList<LiveSoundFragment> obtainedByHlsPlaylist = new LinkedList<>();

    @Getter
    private final PriorityQueue<LiveSoundFragment> regularQueue = new PriorityQueue<>(Comparator.comparing(LiveSoundFragment::getQueueNum));

    @Getter
    private final LinkedList<LiveSoundFragment> prioritizedQueue = new LinkedList<>();

    @Getter
    private final String brand;
    private final UUID brandId;

    private final SoundFragmentService soundFragmentService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AudioSegmentationService segmentationService;
    private final SongSupplier songSupplier;
    private final BrandSoundFragmentUpdateService brandSoundFragmentUpdateService;
    private final RadioStation radioStation;
    private final String tempBaseDir;
    private Long lastPrioritizedDrainAt;
    private boolean playedRegularSinceDrain;
    private volatile long lastStarvingFeedTime = 0;

    public PlaylistManager(BroadcasterConfig broadcasterConfig, IStreamManager streamManager, SongSupplier songSupplier, BrandSoundFragmentUpdateService brandSoundFragmentUpdateService) {
        this.soundFragmentService = streamManager.getSoundFragmentService();
        this.segmentationService = streamManager.getSegmentationService();
        this.radioStation = streamManager.getRadioStation();
        this.songSupplier = songSupplier;
        this.brandSoundFragmentUpdateService = brandSoundFragmentUpdateService;
        this.brand = radioStation.getSlugName();
        this.brandId = radioStation.getId();
        this.tempBaseDir = broadcasterConfig.getPathUploads() + "/playlist-processing";
        LOGGER.info("Created PlaylistManager for brand: {}", brand);
    }

    public void startSelfManaging() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (regularQueue.size() <= TRIGGER_SELF_MANAGING) {
                    feedFragments(2, false);
                } else {
                    LOGGER.debug("Skipping fragment addition - ready queue is full ({} items)",
                            READY_QUEUE_MAX_SIZE);
                }
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 30, SELF_MANAGING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void feedFragments(int maxQuantity, boolean useCooldown) {
        if (useCooldown) {
            long now = System.currentTimeMillis();
            if (now - lastStarvingFeedTime < STARVING_FEED_COOLDOWN_MILLIS) {
                LOGGER.debug("Station starving but cooldown active, waiting {} ms",
                        STARVING_FEED_COOLDOWN_MILLIS - (now - lastStarvingFeedTime));
                return;
            }
            lastStarvingFeedTime = now;
        }

        int remaining = Math.max(0, REGULAR_BUFFER_MAX - regularQueue.size());

        if (remaining == 0) {
            LOGGER.debug("Skipping addFragments - regular buffer at cap {} for brand {}", REGULAR_BUFFER_MAX, brand);
            return;
        }

        int quantityToFetch = Math.min(remaining, maxQuantity);
        LOGGER.info("Adding {} fragments for brand {}", quantityToFetch, brand);

        songSupplier.getBrandSongs(brand, PlaylistItemType.SONG, quantityToFetch)
                .onItem().transformToMulti(soundFragments ->
                        Multi.createFrom().iterable(soundFragments)
                )
                .onItem().call(fragment -> {
                    try {
                        List<FileMetadata> metadataList = fragment.getFileMetadataList();
                        FileMetadata metadata = metadataList.get(0);

                        return soundFragmentService.getFileBySlugName(
                                        fragment.getId(),
                                        metadata.getSlugName(),
                                        SuperUser.build()
                                )
                                .chain(fetchedMetadata -> fetchedMetadata.materializeFileStream(tempBaseDir)
                                        .onItem().transform(tempFilePath -> fetchedMetadata))
                                .chain(materializedMetadata ->
                                        addFragmentToSlice(fragment, materializedMetadata, radioStation.getBitRate()));
                    } catch (Exception e) {
                        LOGGER.warn("Skipping fragment due to metadata error: {}", e.getMessage());
                        return Uni.createFrom().item(false);
                    }
                })
                .collect().asList()
                .subscribe().with(
                        processedItems -> {
                            LOGGER.info("Successfully processed and added {} fragments for brand {}.", processedItems.size(), brand);
                        },
                        error -> {
                            LOGGER.error("Error during the processing of fragments for brand {}: {}", brand, error.getMessage(), error);
                            radioStation.setStatus(RadioStationStatus.SYSTEM_ERROR);
                        }
                );
    }

    public Uni<Boolean> addFragmentToSlice(SoundFragment soundFragment, int priority, long maxRate, MergingType mergingType) {
        try {
            List<FileMetadata> metadataList = soundFragment.getFileMetadataList();
            FileMetadata metadata = metadataList.get(0);
            LiveSoundFragment liveSoundFragment = new LiveSoundFragment();
            SongMetadata songMetadata = new SongMetadata(soundFragment.getTitle(), soundFragment.getArtist());
            songMetadata.setMergingType(mergingType);
            liveSoundFragment.setSoundFragmentId(soundFragment.getId());
            liveSoundFragment.setMetadata(songMetadata);
            return segmentationService.slice(songMetadata, metadata.getTemporaryFilePath(), List.of(maxRate))
                    .onItem().transformToUni(segments -> {
                        if (segments.isEmpty()) {
                            LOGGER.warn("Slicing from metadata {} resulted in zero segments.", metadata.getFileKey());
                            return Uni.createFrom().item(false);
                        }

                        liveSoundFragment.setSegments(segments);
                        boolean isAiDjSubmit = priority <= 10;

                        if (isAiDjSubmit) {
                            prioritizedQueue.add(liveSoundFragment);
                            LOGGER.info("Added AI submit fragment for brand {}: {}", brand, metadata.getFileOriginalName());
                            if (prioritizedQueue.size() >= BACKPRESSURE_ON) {
                                radioStation.setStatus(RadioStationStatus.QUEUE_SATURATED);
                            }
                        } else {
                            if (regularQueue.size() >= REGULAR_BUFFER_MAX) {
                                LOGGER.debug("Refusing to add regular fragment; buffer full ({}). Brand: {}", REGULAR_BUFFER_MAX, brand);
                                return Uni.createFrom().item(false);
                            }
                            regularQueue.add(liveSoundFragment);
                            LOGGER.info("Added and sliced fragment from metadata for brand {}: {}", brand, metadata.getFileOriginalName());
                        }
                        return Uni.createFrom().item(true);
                    });
        } catch (Exception e) {
            LOGGER.warn("Skipping fragment due to metadata error, position 658: {}", e.getMessage());
            return Uni.createFrom().item(false);
        }
    }

    @Deprecated
    public Uni<Boolean> addFragmentToSlice(BrandSoundFragment brandSoundFragment, long bitRate) {
        try {
            List<FileMetadata> metadataList = brandSoundFragment.getSoundFragment().getFileMetadataList();
            FileMetadata metadata = metadataList.get(0);
            return this.addFragmentToSlice(brandSoundFragment.getSoundFragment(), metadata, bitRate);
        } catch (Exception e) {
            LOGGER.warn("Skipping fragment due to metadata error, position 658: {}", e.getMessage());
            return Uni.createFrom().item(false);
        }
    }

    private Uni<Boolean> addFragmentToSlice(SoundFragment soundFragment, FileMetadata materializedMetadata, long maxRate) {
        LiveSoundFragment liveSoundFragment = new LiveSoundFragment();
        SongMetadata songMetadata = new SongMetadata(soundFragment.getTitle(), soundFragment.getArtist());
        liveSoundFragment.setSoundFragmentId(soundFragment.getId());
        liveSoundFragment.setMetadata(songMetadata);
        return segmentationService.slice(songMetadata, materializedMetadata.getTemporaryFilePath(), List.of(maxRate))
                .onItem().transformToUni(segments -> {
                    if (segments.isEmpty()) {
                        LOGGER.warn("Slicing from metadata {} resulted in zero segments.", materializedMetadata.getFileKey());
                        return Uni.createFrom().item(false);
                    }

                    liveSoundFragment.setSegments(segments);
                    boolean isAiDjSubmit = liveSoundFragment.getQueueNum() == 10;

                    if (isAiDjSubmit) {
                        prioritizedQueue.add(liveSoundFragment);
                        LOGGER.info("Added AI submit fragment for brand {}: {}", brand, materializedMetadata.getFileOriginalName());
                        if (prioritizedQueue.size() >= BACKPRESSURE_ON) {
                            radioStation.setStatus(RadioStationStatus.QUEUE_SATURATED);
                        }
                    } else {
                        if (regularQueue.size() >= REGULAR_BUFFER_MAX) {
                            LOGGER.debug("Refusing to add regular fragment; buffer full ({}). Brand: {}", REGULAR_BUFFER_MAX, brand);
                            return Uni.createFrom().item(false);
                        }
                        regularQueue.add(liveSoundFragment);
                        LOGGER.info("Added and sliced fragment from metadata for brand {}: {}", brand, materializedMetadata.getFileOriginalName());
                    }
                    return Uni.createFrom().item(true);
                });
    }

    public LiveSoundFragment getNextFragment() {
        if (!prioritizedQueue.isEmpty()) {
            LiveSoundFragment nextFragment = prioritizedQueue.poll();
            if (prioritizedQueue.isEmpty() && radioStation.getStatus() == RadioStationStatus.QUEUE_SATURATED) {
                lastPrioritizedDrainAt = System.currentTimeMillis();
                playedRegularSinceDrain = false;
            }
            moveFragmentToProcessedList(nextFragment);
            return nextFragment;
        }

        if (radioStation.getStatus() == RadioStationStatus.QUEUE_SATURATED) {
            long now = System.currentTimeMillis();
            boolean cooldownElapsed = lastPrioritizedDrainAt != null && (now - lastPrioritizedDrainAt) >= BACKPRESSURE_COOLDOWN_MILLIS;
            if (cooldownElapsed) {
                radioStation.setStatus(RadioStationStatus.ON_LINE);
                lastPrioritizedDrainAt = null;
                playedRegularSinceDrain = false;
                LOGGER.info("Backpressure released by cooldown - switching back to ON_LINE");
            }
        }

        if (radioStation.getStatus() == RadioStationStatus.QUEUE_SATURATED) {
            long now = System.currentTimeMillis();
            if (lastPrioritizedDrainAt == null) {
                lastPrioritizedDrainAt = now;
                playedRegularSinceDrain = false;
            }
        }

        if (!regularQueue.isEmpty()) {
            LiveSoundFragment nextFragment = regularQueue.poll();
            if (radioStation.getStatus() == RadioStationStatus.QUEUE_SATURATED && prioritizedQueue.isEmpty()) {
                playedRegularSinceDrain = true;
            }
            moveFragmentToProcessedList(nextFragment);
            if (radioStation.getStatus() == RadioStationStatus.QUEUE_SATURATED && prioritizedQueue.isEmpty()) {
                long now = System.currentTimeMillis();
                boolean cooldownElapsed = lastPrioritizedDrainAt != null && (now - lastPrioritizedDrainAt) >= BACKPRESSURE_COOLDOWN_MILLIS;
                if (playedRegularSinceDrain || cooldownElapsed) {
                    radioStation.setStatus(RadioStationStatus.ON_LINE);
                    lastPrioritizedDrainAt = null;
                    playedRegularSinceDrain = false;
                    LOGGER.info("Backpressure released - switching back to ON_LINE");
                }
            }
            return nextFragment;
        }

        feedFragments(1, true);
        return null;
    }

    public PlaylistManagerStats getStats() {
        return PlaylistManagerStats.from(this);
    }

    private void moveFragmentToProcessedList(LiveSoundFragment fragmentToMove) {
        if (fragmentToMove != null) {
            slicedFragmentsLock.writeLock().lock();
            try {
                obtainedByHlsPlaylist.add(fragmentToMove);
                brandSoundFragmentUpdateService.updatePlayedCountAsync(brandId, fragmentToMove.getSoundFragmentId())
                        .subscribe().with(
                                unused -> {},
                                error -> LOGGER.error("Failed to update played count: {}", error.getMessage(), error)
                        );
                if (obtainedByHlsPlaylist.size() > PROCESSED_QUEUE_MAX_SIZE) {
                    LiveSoundFragment removed = obtainedByHlsPlaylist.poll();
                    LOGGER.debug("Removed oldest fragment from processed queue: {}",
                            removed.getMetadata());
                }
            } finally {
                slicedFragmentsLock.writeLock().unlock();
            }
        }
    }
}