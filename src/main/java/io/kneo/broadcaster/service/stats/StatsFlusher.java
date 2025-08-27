package io.kneo.broadcaster.service.stats;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class StatsFlusher {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsFlusher.class);

    @Inject
    StatsAccumulator statsAccumulator;

    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Stats scheduler started");
    }

    void onStop(@Observes ShutdownEvent ev) {
        LOGGER.info("Application shutting down, flushing remaining stats...");
        try {
            statsAccumulator.flushAllStats()
                    .await().atMost(java.time.Duration.ofSeconds(30));
            LOGGER.info("Final stats flush completed");
        } catch (Exception e) {
            LOGGER.error("Failed to flush stats on shutdown", e);
        }
    }

    @Scheduled(every = "3m", identity = "stats-flush")
    public Uni<Void> scheduledFlush() {
        long pendingCount = statsAccumulator.getTotalPendingAccesses();

        if (pendingCount == 0) {
            LOGGER.debug("Scheduled flush: no pending stats");
            return Uni.createFrom().voidItem();
        }

        LOGGER.debug("Scheduled flush starting: {} pending accesses across {} stations",
                pendingCount, statsAccumulator.getPendingStatsCount());

        return statsAccumulator.flushAllStats()
                .onItem().invoke(() ->
                        LOGGER.debug("Scheduled flush completed successfully")
                )
                .onFailure().invoke(failure ->
                        LOGGER.error("Scheduled flush failed", failure)
                );
    }
}