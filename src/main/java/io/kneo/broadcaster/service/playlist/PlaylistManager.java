package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStatsDTO;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.live.SongMetadata;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.service.live.AiHelperService;
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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlaylistManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistManager.class);
    private static final int SELF_MANAGING_INTERVAL_SECONDS = 100;
    private static final int REGULAR_BUFFER_MAX = 2;
    private static final int TRIGGER_SELF_MANAGING = 2;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 2;
    private static final long STARVING_FEED_COOLDOWN_MILLIS = 20_000L;

    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();

    @Getter
    private final LinkedList<LiveSoundFragment> obtainedByHlsPlaylist = new LinkedList<>();

    @Getter
    private final PriorityQueue<LiveSoundFragment> regularQueue = new PriorityQueue<>(Comparator.comparing(LiveSoundFragment::getQueueNum));

    @Getter
    private final PriorityQueue<LiveSoundFragment> prioritizedQueue =
            new PriorityQueue<>(
                    Comparator
                            .comparing(LiveSoundFragment::getPriority)
                            .thenComparing(LiveSoundFragment::getQueueNum)
            );

    @Getter
    private final String brandSlug;
    private final UUID masterBrandId;
    private final SoundFragmentService soundFragmentService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AudioSegmentationService segmentationService;
    private final ISupplier songSupplier;
    private final BrandSoundFragmentUpdateService brandSoundFragmentUpdateService;
    private final IStream stream;
    private final String tempBaseDir;
    private volatile long lastStarvingFeedTime = 0;
    private final int segmentDuration;
    @Getter
    private final LinkedList<LiveSoundFragment> fragmentsForMp3 = new LinkedList<>();
    private final AiHelperService aiHelperService;
    private static final Random RANDOM = new Random();
    private LiveSoundFragment waitingStateFragment;
    private boolean isWaitingStateActive = false;


    public PlaylistManager(HlsPlaylistConfig hlsPlaylistConfig,
                           BroadcasterConfig broadcasterConfig,
                           IStreamManager streamManager,
                           ISupplier songSupplier,
                           BrandSoundFragmentUpdateService brandSoundFragmentUpdateService,
                           AiHelperService aiHelperService
    ) {
        this.soundFragmentService = streamManager.getSoundFragmentService();
        this.segmentationService = streamManager.getSegmentationService();
        this.stream = streamManager.getStream();
        this.songSupplier = songSupplier;
        this.brandSoundFragmentUpdateService = brandSoundFragmentUpdateService;
        this.aiHelperService = aiHelperService;
        if (stream.getMasterBrand() != null) {
            this.masterBrandId = stream.getMasterBrand().getId();
            this.brandSlug = stream.getMasterBrand().getSlugName();
        } else {
            this.masterBrandId = stream.getId();
            this.brandSlug = stream.getSlugName();
        }
        this.tempBaseDir = broadcasterConfig.getPathUploads() + "/playlist-processing";
        this.segmentDuration = hlsPlaylistConfig.getSegmentDuration();
        LOGGER.info("Created PlaylistManager for brand: {}", brandSlug);
        
        if (stream instanceof OneTimeStream) {
            initializeWaitingState();
        }
    }

    public void startSelfManaging() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (regularQueue.size() <= TRIGGER_SELF_MANAGING) {
                    if (RANDOM.nextDouble() < 0.5) {
                        feedFragments(1, false);
                    } else {
                        feedFragments(2, false);
                    }
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
            LOGGER.debug("Skipping addFragments - regular buffer at cap {} for brand {}", REGULAR_BUFFER_MAX, brandSlug);
            return;
        }

        int quantityToFetch = Math.min(remaining, maxQuantity);
        LOGGER.info("Adding {} fragments for brand {}", quantityToFetch, brandSlug);

        songSupplier.getBrandSongs(brandSlug, masterBrandId, PlaylistItemType.SONG, quantityToFetch)
                .onItem().transformToMulti(soundFragments ->
                        Multi.createFrom().iterable(soundFragments)
                )
                .onItem().call(fragment -> {
                    try {
                        List<FileMetadata> metadataList = fragment.getFileMetadataList();
                        FileMetadata metadata = metadataList.getFirst();

                        return soundFragmentService.getFileBySlugName(
                                        fragment.getId(),
                                        metadata.getSlugName(),
                                        SuperUser.build()
                                )
                                .chain(fetchedMetadata -> {
                                    //LOGGER.info("File to materialize: {}", fetchedMetadata.getFileOriginalName());
                                    return fetchedMetadata.materializeFileStream(tempBaseDir)
                                            .onItem().transform(tempFilePath -> fetchedMetadata);
                                })
                                .chain(materializedMetadata ->
                                        addFragmentToSlice(fragment, materializedMetadata, stream.getBitRate()));
                    } catch (Exception e) {
                        LOGGER.warn("Skipping fragment due to metadata error: {}", e.getMessage());
                        return Uni.createFrom().item(false);
                    }
                })
                .collect().asList()
                .subscribe().with(
                        processedItems -> {
                            LOGGER.info("Successfully processed and added {} fragments for brand {}.", processedItems.size(), brandSlug);
                        },
                        error -> {
                            LOGGER.error("Error during the processing of fragments for brand {}: {}", brandSlug, error.getMessage(), error);
                            stream.setStatus(RadioStationStatus.SYSTEM_ERROR);
                        }
                );
    }

    public Uni<Boolean> addFragmentToSlice(SoundFragment soundFragment, int priority, long maxRate, MergingType mergingType, AddToQueueDTO queueDTO) {
        try {
            List<FileMetadata> metadataList = soundFragment.getFileMetadataList();
            FileMetadata metadata = metadataList.getFirst();
            LiveSoundFragment liveSoundFragment = new LiveSoundFragment();
            SongMetadata songMetadata = new SongMetadata(soundFragment.getTitle(), soundFragment.getArtist());
            songMetadata.setMergingType(mergingType);
            liveSoundFragment.setSoundFragmentId(soundFragment.getId());
            liveSoundFragment.setMetadata(songMetadata);
            liveSoundFragment.setSourceFilePath(metadata.getTemporaryFilePath());
            liveSoundFragment.setPriority(queueDTO.getPriority());

            if (soundFragment.getSource() == SourceType.CONTRIBUTION) {

            }

            return segmentationService.slice(songMetadata, metadata.getTemporaryFilePath(), List.of(maxRate))
                    .onItem().transformToUni(segments -> {
                        if (segments.isEmpty()) {
                            LOGGER.warn("Slicing from metadata {} set in zero segments.", metadata.getFileKey());
                            return Uni.createFrom().item(false);
                        }

                        liveSoundFragment.setSegments(segments);
                        boolean curated = priority <= 12;

                        if (curated) {
                            if (queueDTO.getPriority() != null && queueDTO.getPriority() <= 9) {
                                prioritizedQueue.clear();
                                
                                if (queueDTO.getPriority() <= 8) {
                                    slicedFragmentsLock.writeLock().lock();
                                    try {
                                        obtainedByHlsPlaylist.clear();
                                        LOGGER.warn("Priority {} triggered immediate interruption. Cleared active playlist for brand {}",
                                                queueDTO.getPriority(), brandSlug);
                                    } finally {
                                        slicedFragmentsLock.writeLock().unlock();
                                    }
                                }
                                
                                aiHelperService.addMessage(
                                        brandSlug,
                                    AiDjStatsDTO.MessageType.INFO,
                                    String.format("Sound fragment '%s' with higher priority (%s)",
                                            soundFragment.getTitle(), queueDTO.getPriority())
                                );
                            }
                            
                            prioritizedQueue.add(liveSoundFragment);
                            LOGGER.info("Added submit fragment for brand {}: {}", brandSlug, metadata.getTemporaryFilePath());
                        } else {
                            if (regularQueue.size() >= REGULAR_BUFFER_MAX) {
                                LOGGER.debug("Refusing to add regular fragment; buffer full ({}). Brand: {}", REGULAR_BUFFER_MAX, brandSlug);
                                return Uni.createFrom().item(false);
                            }
                            regularQueue.add(liveSoundFragment);
                            LOGGER.info("Added and sliced fragment from metadata for brand {}: {}", brandSlug, metadata.getFileOriginalName());
                        }
                        //memoryService.commitHistory(brand, liveSoundFragment.getSoundFragmentId()).subscribe().asCompletionStage();
                        return Uni.createFrom().item(true);
                    });
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
        liveSoundFragment.setSourceFilePath(materializedMetadata.getTemporaryFilePath());
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
                        LOGGER.info("Added AI submit fragment for brand {}: {}", brandSlug, materializedMetadata.getFileOriginalName());
                    } else {
                        if (regularQueue.size() >= REGULAR_BUFFER_MAX) {
                            LOGGER.debug("Refusing to add regular fragment; buffer is full ({}). Brand: {}", REGULAR_BUFFER_MAX, brandSlug);
                            return Uni.createFrom().item(false);
                        }
                        regularQueue.add(liveSoundFragment);
                        LOGGER.info("Added and sliced fragment from for brand {}: {}", brandSlug, materializedMetadata.getFileOriginalName());
                    }
                    //memoryService.commitHistory(brand, liveSoundFragment.getSoundFragmentId()).subscribe().asCompletionStage();
                    return Uni.createFrom().item(true);
                });
    }

    public LiveSoundFragment getNextFragment() {
        if (!prioritizedQueue.isEmpty()) {
            isWaitingStateActive = false;
            LiveSoundFragment nextFragment = prioritizedQueue.poll();
            moveFragmentToProcessedList(nextFragment);
            return nextFragment;
        }

        if (!regularQueue.isEmpty()) {
            isWaitingStateActive = false;
            LiveSoundFragment nextFragment = regularQueue.poll();
            moveFragmentToProcessedList(nextFragment);
            return nextFragment;
        }

        if (stream.getManagedBy() != ManagedBy.DJ) {
            feedFragments(1, true);
        }
        
        if (waitingStateFragment != null && isWaitingStateActive) {
            LOGGER.debug("Looping waiting state for brand: {}", brandSlug);
            return waitingStateFragment;
        }
        
        return null;
    }

    public PlaylistManagerStats getStats() {
        return new PlaylistManagerStats(this, segmentDuration);
    }

    private void moveFragmentToProcessedList(LiveSoundFragment fragmentToPlay) {
        if (fragmentToPlay != null) {
            slicedFragmentsLock.writeLock().lock();
            try {
                obtainedByHlsPlaylist.add(fragmentToPlay);
                brandSoundFragmentUpdateService.updatePlayedCountAsync(masterBrandId, fragmentToPlay.getSoundFragmentId())
                        .subscribe().with(
                                unused -> {
                                },
                                error -> LOGGER.error("Failed to update played count: {}, brandId: {}", error.getMessage(), masterBrandId, error)
                        );
                LOGGER.info(">>> moveFragmentToProcessedList START for brand {} fragment {}", brandSlug, fragmentToPlay.getMetadata());
                fragmentsForMp3.add(fragmentToPlay);
                while (fragmentsForMp3.size() > 2) {
                    fragmentsForMp3.removeFirst();
                }

                LOGGER.info("Queued fragment for brand={} id={}", brandSlug, fragmentToPlay.getSoundFragmentId());
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

    private void initializeWaitingState() {
        try {
            InputStream resourceStream = getClass().getClassLoader().getResourceAsStream("Waiting_State.wav");
            if (resourceStream == null) {
                LOGGER.warn("Waiting_State.wav not found in resources for brand: {}", brandSlug);
                return;
            }

            Path tempWaitingFile = Files.createTempFile("waiting_state_", ".wav");
            Files.copy(resourceStream, tempWaitingFile, StandardCopyOption.REPLACE_EXISTING);
            resourceStream.close();

            SongMetadata waitingMetadata = new SongMetadata("Waiting State", "System");
            segmentationService.slice(waitingMetadata, tempWaitingFile.toString(), List.of(stream.getBitRate()))
                    .subscribe().with(
                            segments -> {
                                if (segments.isEmpty()) {
                                    LOGGER.warn("Failed to slice Waiting_State.wav for brand: {}", brandSlug);
                                    return;
                                }
                                waitingStateFragment = new LiveSoundFragment();
                                waitingStateFragment.setSoundFragmentId(UUID.randomUUID());
                                waitingStateFragment.setMetadata(waitingMetadata);
                                waitingStateFragment.setSourceFilePath(tempWaitingFile);
                                waitingStateFragment.setSegments(segments);
                                waitingStateFragment.setPriority(999);
                                isWaitingStateActive = true;
                                LOGGER.info("Waiting state initialized for brand: {} with {} segments", brandSlug, segments.get(stream.getBitRate()).size());
                            },
                            error -> LOGGER.error("Error slicing Waiting_State.wav for brand {}: {}", brandSlug, error.getMessage(), error)
                    );
        } catch (Exception e) {
            LOGGER.error("Error initializing waiting state for brand {}: {}", brandSlug, e.getMessage(), e);
        }
    }
}
