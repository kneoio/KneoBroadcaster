package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.StatusChangeRecord;
import io.kneo.broadcaster.repository.OneTimeStreamRepository;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class MaintenanceCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceCleanupService.class);
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);

    private Cancellable cleanupSubscription;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    BrandService brandService;

    @Inject
    OneTimeStreamRepository oneTimeStreamRepository;

    @Inject
    SoundFragmentService soundFragmentService;

    @Inject
    SoundFragmentRepository soundFragmentRepository;

    void onStart(@Observes StartupEvent event) {
        startCleanupTask();
    }

    private void startCleanupTask() {
        cleanupSubscription = Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(CLEANUP_INTERVAL)
                .onOverflow().drop()
                .onItem().invoke(this::performCleanup)
                .onFailure().invoke(error -> LOGGER.error("Maintenance cleanup timer error", error))
                .subscribe().with(
                        item -> {
                        },
                        failure -> LOGGER.error("Maintenance cleanup subscription failed", failure)
                );
    }

    public void stopCleanupTask() {
        if (cleanupSubscription != null) {
            cleanupSubscription.cancel();
        }
    }

    private void cleanupTemporaryBrands(Long tick, Set<String> activeSlugs) {
        brandService.getAll(1000, 0)
                .onItem().transformToUni(brands -> {
                    List<Uni<Integer>> archiveOps = brands.stream()
                            .filter(b -> b.getIsTemporary() == 1 && b.getArchived() == 0)
                            .filter(b -> !activeSlugs.contains(b.getSlugName()))
                            .map(b -> brandService.archive(b.getId()))
                            .toList();
                    if (archiveOps.isEmpty()) {
                        return Uni.createFrom().item(0);
                    }
                    return Uni.join().all(archiveOps).andFailFast()
                            .onItem().transform(List::size);
                })
                .subscribe().with(
                        archivedCount -> {
                            if (archivedCount != null && archivedCount > 0) {
                                LOGGER.info("Maintenance cleanup (tick: {}) archived {} temporary brands", tick, archivedCount);
                            }
                        },
                        error -> LOGGER.error("Temporary brand cleanup failed (tick: {})", tick, error)
                );
    }

    private void cleanupPendingOneTimeStreams(Long tick) {
        LocalDateTime cutoffTime = LocalDateTime.now().minus(Duration.ofHours(3));
        
        oneTimeStreamRepository.getAll(1000, 0)
                .onItem().transformToUni(streams -> {
                    List<Uni<Void>> deleteOps = streams.stream()
                            .filter(stream -> stream.getStatus() == StreamStatus.PENDING)
                            .filter(stream -> isPendingTooLong(stream, cutoffTime))
                            .map(stream -> {
                                LOGGER.info("Deleting stale PENDING OneTimeStream: slugName={}, id={}, pending since={}",
                                        stream.getSlugName(), stream.getId(), getPendingStatusTimestamp(stream));
                                return oneTimeStreamRepository.delete(stream.getId());
                            })
                            .toList();
                    if (deleteOps.isEmpty()) {
                        return Uni.createFrom().item(0);
                    }
                    return Uni.join().all(deleteOps).andFailFast()
                            .onItem().transform(List::size);
                })
                .subscribe().with(
                        deletedCount -> {
                            if (deletedCount != null && deletedCount > 0) {
                                LOGGER.info("Maintenance cleanup (tick: {}) deleted {} stale PENDING streams", tick, deletedCount);
                            }
                        },
                        error -> LOGGER.error("OneTimeStream cleanup failed (tick: {})", tick, error)
                );
    }

    private boolean isPendingTooLong(OneTimeStream stream, LocalDateTime cutoffTime) {
        LocalDateTime pendingTimestamp = getPendingStatusTimestamp(stream);
        return pendingTimestamp != null && pendingTimestamp.isBefore(cutoffTime);
    }

    private LocalDateTime getPendingStatusTimestamp(OneTimeStream stream) {
        List<StatusChangeRecord> history = stream.getStatusHistory();
        if (history == null || history.isEmpty()) {
            return stream.getCreatedAt();
        }
        
        for (int i = history.size() - 1; i >= 0; i--) {
            StatusChangeRecord record = history.get(i);
            if (record.newStatus() == StreamStatus.PENDING) {
                return record.timestamp();
            }
        }
        
        return stream.getCreatedAt();
    }

    private void performCleanup(Long tick) {
        Set<String> activeSlugs = radioStationPool.getActiveSnapshot();
        LOGGER.info("Maintenance cleanup (tick: {}) - Active slugs in pool (will be excluded): {}", tick, activeSlugs);

        cleanupTemporaryBrands(tick, activeSlugs);
        cleanupPendingOneTimeStreams(tick);
        cleanupExpiredSoundFragments(tick)
                .onItem().transformToUni(count -> {
                    if (count > 0) LOGGER.info("Maintenance cleanup (tick: {}) deleted {} expired SoundFragments", tick, count);
                    return cleanupArchivedSoundFragments(tick);
                })
                .subscribe().with(
                        count -> { if (count > 0) LOGGER.info("Maintenance cleanup (tick: {}) deleted {} archived SoundFragments", tick, count); },
                        failure -> LOGGER.error("SoundFragment cleanup chain failed (tick: {})", tick, failure)
                );
    }

    private Uni<Integer> cleanupExpiredSoundFragments(Long tick) {
        return soundFragmentRepository.findExpiredFragments()
                .onItem().transformToUni(fragmentIds -> {
                    if (fragmentIds.isEmpty()) {
                        return Uni.createFrom().item(0);
                    }
                    List<Uni<Integer>> deleteOps = fragmentIds.stream()
                            .map(fragmentId -> soundFragmentService.hardDelete(fragmentId)
                                    .onFailure().recoverWithItem(error -> {
                                        LOGGER.error("Failed to delete expired SoundFragment {}: {}", fragmentId, error.getMessage(), error);
                                        return 0;
                                    }))
                            .toList();
                    return Uni.join().all(deleteOps).andFailFast()
                            .onItem().transform(results -> results.stream().mapToInt(Integer::intValue).sum());
                });
    }

    private Uni<Integer> cleanupArchivedSoundFragments(Long tick) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusMonths(1);
        return soundFragmentRepository.findArchivedFragments(cutoffDate)
                .onItem().transformToUni(fragmentIds -> {
                    if (fragmentIds.isEmpty()) {
                        return Uni.createFrom().item(0);
                    }
                    List<Uni<Integer>> deleteOps = fragmentIds.stream()
                            .map(fragmentId -> soundFragmentService.hardDelete(fragmentId)
                                    .onFailure().recoverWithItem(error -> {
                                        LOGGER.error("Failed to delete archived SoundFragment {}: {}", fragmentId, error.getMessage(), error);
                                        return 0;
                                    }))
                            .toList();
                    return Uni.join().all(deleteOps).andFailFast()
                            .onItem().transform(results -> results.stream().mapToInt(Integer::intValue).sum());
                });
    }
}
