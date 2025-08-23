package io.kneo.broadcaster.service.stats;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class StatsScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsScheduler.class);

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

    @Scheduled(every = "5m", identity = "stats-flush")
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

    @Getter
    public static class StatsHealth {
        private final int pendingStations;
        private final long totalPendingAccesses;

        public StatsHealth(int pendingStations, long totalPendingAccesses) {
            this.pendingStations = pendingStations;
            this.totalPendingAccesses = totalPendingAccesses;
        }

        @Override
        public String toString() {
            return String.format("StatsHealth{pendingStations=%d, totalPendingAccesses=%d}",
                    pendingStations, totalPendingAccesses);
        }
    }
}