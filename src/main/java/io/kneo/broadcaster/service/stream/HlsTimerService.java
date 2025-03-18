package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class HlsTimerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HlsTimerService.class);

    private final int segmentDurationSeconds;

    // Shared broadcast ticker that drives the entire system
    private Multi<Long> sharedTicker;

    // Self-subscription to keep the stream "hot" even without clients
    private Cancellable keepAliveSubscription;

    @Inject
    public HlsTimerService(HlsPlaylistConfig config) {
        this.segmentDurationSeconds = config.getSegmentDuration();
    }

    @PostConstruct
    void init() {
        LOGGER.info("Initializing HLS Timer Service with segment duration: {}s", segmentDurationSeconds);

        // Calculate the next segment boundary for wall-clock alignment
        Instant now = Instant.now();
        Instant nextBoundary = now.plusSeconds(
                segmentDurationSeconds - (now.getEpochSecond() % segmentDurationSeconds)
        ).truncatedTo(ChronoUnit.SECONDS);

        long initialDelayMillis = nextBoundary.toEpochMilli() - now.toEpochMilli();

        // Create the timer that emits on segment boundaries and share it via broadcast
        sharedTicker = Multi.createFrom().ticks()
                .startingAfter(Duration.ofMillis(initialDelayMillis))
                .every(Duration.ofSeconds(segmentDurationSeconds))
                .onOverflow().drop()
                .map(tick -> {
                    // Get the current segment timestamp (aligned to wall clock)
                    long currentTimestamp = Instant.now().getEpochSecond();
                    // Round down to nearest segment boundary
                    return currentTimestamp - (currentTimestamp % segmentDurationSeconds);
                })
                .broadcast().toAllSubscribers();

        // Keep the ticker alive even when no clients are connected
        keepAliveSubscription = sharedTicker.subscribe().with(
                timestamp -> LOGGER.debug("Timer tick: timestamp={}", timestamp),
                throwable -> LOGGER.error("Timer error", throwable)
        );

        LOGGER.info("HLS Timer Service initialized and active");
    }

    /**
     * Get the broadcast ticker that emits timestamps on segment boundaries
     */
    public Multi<Long> getTicker() {
        return sharedTicker;
    }

    @PreDestroy
    void cleanup() {
        LOGGER.info("Shutting down HLS Timer Service");
        if (keepAliveSubscription != null) {
            keepAliveSubscription.cancel();
        }
    }
}