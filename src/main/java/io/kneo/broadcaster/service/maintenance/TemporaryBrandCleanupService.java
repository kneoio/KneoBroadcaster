package io.kneo.broadcaster.service.maintenance;

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
        Set<String> activeSlugs = radioStationPool.getActiveSlugNamesSnapshot();
        LOGGER.info("Temporary brand cleanup (tick: {}) - Active slugs in pool (will be excluded): {}", tick, activeSlugs);
        
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
                            .onItem().transform(results -> results.size());
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
}
