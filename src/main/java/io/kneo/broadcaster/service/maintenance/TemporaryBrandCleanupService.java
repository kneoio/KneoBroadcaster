package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

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
        List<String> activeSlugs = List.copyOf(radioStationPool.getActiveSlugNamesSnapshot());
        brandService.deleteTemporaryBrands(activeSlugs)
                .subscribe().with(
                        deletedCount -> {
                            if (deletedCount != null && deletedCount > 0) {
                                LOGGER.info("Temporary brand cleanup (tick: {}) deleted {} brands", tick, deletedCount);
                            }
                        },
                        error -> LOGGER.error("Temporary brand cleanup failed (tick: {})", tick, error)
                );
    }
}
