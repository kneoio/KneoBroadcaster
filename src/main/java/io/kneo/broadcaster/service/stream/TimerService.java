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
public class TimerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimerService.class);
    private final int segmentDurationSeconds;
    private Multi<Long> sharedTicker;
    private Cancellable keepAliveSubscription;

    @Inject
    public TimerService(HlsPlaylistConfig config) {
        this.segmentDurationSeconds = 10;
    }

    @PostConstruct
    void init() {
        LOGGER.info("Initializing Timer Service with segment duration: {}s", segmentDurationSeconds);
        Instant now = Instant.now();
        Instant nextBoundary = now.plusSeconds(
                segmentDurationSeconds - (now.getEpochSecond() % segmentDurationSeconds)
        ).truncatedTo(ChronoUnit.SECONDS);

        long initialDelayMillis = nextBoundary.toEpochMilli() - now.toEpochMilli();
        sharedTicker = Multi.createFrom().ticks()
                .startingAfter(Duration.ofMillis(initialDelayMillis))
                .every(Duration.ofSeconds(segmentDurationSeconds))
                .onOverflow().drop()
                .map(tick -> {
                    long currentTimestamp = Instant.now().getEpochSecond();
                    return currentTimestamp - (currentTimestamp % segmentDurationSeconds);
                })
                .broadcast().toAllSubscribers();
        keepAliveSubscription = sharedTicker.subscribe().with(
                timestamp -> LOGGER.debug("Timer tick: timestamp={}", timestamp),
                throwable -> LOGGER.error("c error", throwable)
        );

        LOGGER.info("Timer Service initialized and active");
    }

    public Multi<Long> getTicker() {
        return sharedTicker;
    }

    @PreDestroy
    void cleanup() {
        LOGGER.info("Shutting down Timer Service");
        if (keepAliveSubscription != null) {
            keepAliveSubscription.cancel();
        }
    }
}