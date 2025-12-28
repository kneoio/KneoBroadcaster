package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.StatusChangeRecord;
import io.kneo.broadcaster.repository.OneTimeStreamRepository;
import io.kneo.broadcaster.service.BrandService;
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
public class TemporaryBrandCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TemporaryBrandCleanupService.class);
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(5);
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(1);

    private Cancellable cleanupSubscription;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    BrandService brandService;

    @Inject
    OneTimeStreamRepository oneTimeStreamRepository;

    void onStart(@Observes StartupEvent event) {
        startCleanupTask();
    }

    private void startCleanupTask() {
        cleanupSubscription = Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(CLEANUP_INTERVAL)
                .onOverflow().drop()
                .onItem().invoke(this::performCleanup)
                .onFailure().invoke(error -> LOGGER.error("Temporary brand cleanup timer error", error))
                .subscribe().with(
                        item -> {
                        },
                        failure -> LOGGER.error("Temporary brand cleanup subscription failed", failure)
                );
    }

    public void stopCleanupTask() {
        if (cleanupSubscription != null) {
            cleanupSubscription.cancel();
        }
    }

    private void performCleanup(Long tick) {
        Set<String> activeSlugs = radioStationPool.getActiveSnapshot();
        LOGGER.info("Temporary brand cleanup (tick: {}) - Active slugs in pool (will be excluded): {}", tick, activeSlugs);
        
        cleanupTemporaryBrands(tick, activeSlugs);
        cleanupPendingOneTimeStreams(tick);
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
                                LOGGER.info("Temporary brand cleanup (tick: {}) archived {} brands", tick, archivedCount);
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
                            .filter(stream -> stream.getStatus() == RadioStationStatus.PENDING)
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
                                LOGGER.info("OneTimeStream cleanup (tick: {}) deleted {} stale PENDING streams", tick, deletedCount);
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
            if (record.newStatus() == RadioStationStatus.PENDING) {
                return record.timestamp();
            }
        }
        
        return stream.getCreatedAt();
    }
}
