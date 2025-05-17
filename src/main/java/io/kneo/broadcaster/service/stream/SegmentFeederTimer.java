package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class SegmentFeederTimer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentFeederTimer.class);
    private static final int DURATION_SEC = 4;
    private Multi<Long> ticker;
    private Cancellable subscription;

    private Multi<Long> createTicker() {
        LOGGER.info("Creating Timer with duration: {}s", DURATION_SEC);
        Instant now = Instant.now();
        long secondsUntilNextBoundary = DURATION_SEC - (now.getEpochSecond() % DURATION_SEC);
        Instant nextBoundary = now.plusSeconds((int) secondsUntilNextBoundary)
                .truncatedTo(ChronoUnit.SECONDS);

        long initialDelayMillis = nextBoundary.toEpochMilli() - now.toEpochMilli();
        Multi<Long> ticker = Multi.createFrom().ticks()
                .startingAfter(Duration.ofMillis(initialDelayMillis))
                .every(Duration.ofSeconds(DURATION_SEC))
                .onOverflow().drop()
                .map(tick -> {
                    long currentTimestamp = Instant.now().getEpochSecond();
                    return currentTimestamp - (currentTimestamp % DURATION_SEC);
                })
                .broadcast().toAllSubscribers();

        subscription = ticker.subscribe().with(
                timestamp -> LOGGER.debug("Timer tick: timestamp={}", timestamp),
                throwable -> LOGGER.error("Timer error", throwable)
        );
        return ticker;
    }

    public Multi<Long> getTicker() {
        if (ticker == null) {
            ticker = createTicker();
        }
        return ticker;
    }

    @PreDestroy
    void cleanup() {
        LOGGER.info("Shutting down SegmentFeederTimer");
        if (subscription != null) {
            LOGGER.debug("Cancelling subscription");
            subscription.cancel();
        }
        LOGGER.info("SegmentFeederTimer shutdown complete.");
    }
}