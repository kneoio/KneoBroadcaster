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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SegmentFeederTimer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentFeederTimer.class);
    private final int defaultSegmentDurationSeconds;
    private final Map<String, Multi<Long>> sharedTickers = new ConcurrentHashMap<>();
    private final Map<String, Cancellable> keepAliveSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Integer> segmentDurations = new ConcurrentHashMap<>();

    @Inject
    public SegmentFeederTimer(HlsPlaylistConfig config) {
        this.defaultSegmentDurationSeconds = 10; // Default if not configured per brand
    }

    @PostConstruct
    void init() {
        LOGGER.info("SegmentFeederTimer initialized.");
    }

    private Multi<Long> createTicker(String brand, int segmentDuration) {
        LOGGER.info("Creating Timer for brand: {} with segment duration: {}s", brand, segmentDuration);
        Instant now = Instant.now();
        Instant nextBoundary = now.plusSeconds(
                segmentDuration - (now.getEpochSecond() % segmentDuration)
        ).truncatedTo(ChronoUnit.SECONDS);

        long initialDelayMillis = nextBoundary.toEpochMilli() - now.toEpochMilli();
        Multi<Long> ticker = Multi.createFrom().ticks()
                .startingAfter(Duration.ofMillis(initialDelayMillis))
                .every(Duration.ofSeconds(segmentDuration))
                .onOverflow().drop()
                .map(tick -> {
                    long currentTimestamp = Instant.now().getEpochSecond();
                    return currentTimestamp - (currentTimestamp % segmentDuration);
                })
                .broadcast().toAllSubscribers();

        Cancellable subscription = ticker.subscribe().with(
                timestamp -> LOGGER.debug("Timer tick for brand {}: timestamp={}", brand, timestamp),
                throwable -> LOGGER.error("Timer error for brand {}", brand, throwable)
        );
        keepAliveSubscriptions.put(brand, subscription);
        return ticker;
    }

    public Multi<Long> getTicker(String brand) {
        return sharedTickers.computeIfAbsent(brand, b -> {
            int duration = segmentDurations.getOrDefault(b, defaultSegmentDurationSeconds);
            return createTicker(b, duration);
        });
    }


    @PreDestroy
    void cleanup() {
        LOGGER.info("Shutting down SegmentFeederTimer");
        keepAliveSubscriptions.forEach((brand, subscription) -> {
            LOGGER.debug("Cancelling subscription for brand: {}", brand);
            subscription.cancel();
        });
        sharedTickers.clear();
        segmentDurations.clear();
        keepAliveSubscriptions.clear();
        LOGGER.info("SegmentFeederTimer shutdown complete.");
    }
}