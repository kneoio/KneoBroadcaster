package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;

@ApplicationScoped
public class StationInactivityChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(StationInactivityChecker.class);
    private static final int INTERVAL_SECONDS = 300;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(100);
    private static final int IDLE_THRESHOLD_MINUTES = 5;
    private static final int STOP_REMOVE_THRESHOLD_MINUTES = 15;

    @Inject
    RadioStationService radioStationService;

    @Inject
    RadioStationPool radioStationPool;

    private Cancellable cleanupSubscription;

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Starting station inactivity checker.");
        startCleanupTask();
    }

    void onShutdown(@Observes ShutdownEvent event) {
        LOGGER.info("Shutting down station inactivity checker.");
        stopCleanupTask();
    }

    private void startCleanupTask() {
        cleanupSubscription = getTicker()
                .onItem().call(this::checkStationActivity)
                .onFailure().invoke(error -> LOGGER.error("Timer error", error))
                .onFailure().retry().withBackOff(Duration.ofSeconds(10)).atMost(3)
                .subscribe().with(
                        item -> {
                        },
                        failure -> LOGGER.error("Subscription failed", failure)
                );
    }

    private Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(Duration.ofSeconds(INTERVAL_SECONDS))
                .onOverflow().drop();
    }

    public void stopCleanupTask() {
        if (cleanupSubscription != null) {
            cleanupSubscription.cancel();
            cleanupSubscription = null;
        }
    }

    private Uni<Void> checkStationActivity(Long tick) {
        LOGGER.info("Station inactivity checking...");
        OffsetDateTime idleThreshold = OffsetDateTime.now().minusMinutes(IDLE_THRESHOLD_MINUTES);
        OffsetDateTime stopThreshold = OffsetDateTime.now().minusMinutes(STOP_REMOVE_THRESHOLD_MINUTES);
        Collection<RadioStation> onlineStations = radioStationPool.getOnlineStationsSnapshot();

        LOGGER.info("Currently, there are {} active radio stations.", onlineStations.size());

        return Multi.createFrom().iterable(onlineStations)
                .onItem().transformToUni(radioStation ->
                        radioStationService.getStats(radioStation.getSlugName())
                                .onItem().transformToUni(stats -> {
                                    String slug = radioStation.getSlugName();
                                    if (stats != null && stats.getLastAccessTime() != null) {
                                        Instant lastAccessInstant = stats.getLastAccessTime().toInstant();
                                        LOGGER.info("Station '{}' last requested at: {}", slug, stats.getLastAccessTime());
                                        if (lastAccessInstant.isBefore(stopThreshold.toInstant())) {
                                            LOGGER.info("Station {} inactive for {} minutes, stopping and removing.",
                                                    slug, STOP_REMOVE_THRESHOLD_MINUTES);
                                            radioStation.setStatus(RadioStationStatus.OFF_LINE);
                                            return radioStationPool.stopAndRemove(slug)
                                                    .replaceWithVoid();
                                        } else if (lastAccessInstant.isBefore(idleThreshold.toInstant())) {
                                            LOGGER.info("Station {} has been inactive for {} minutes, setting status to IDLE.",
                                                    slug, IDLE_THRESHOLD_MINUTES);
                                            radioStation.setStatus(RadioStationStatus.IDLE);
                                        }
                                    } else {
                                        LOGGER.info("Station '{}' has no last access time recorded, stopping and removing.", slug);
                                        radioStation.setStatus(RadioStationStatus.OFF_LINE);
                                        return radioStationPool.stopAndRemove(slug)
                                                .replaceWithVoid();
                                    }
                                    return Uni.createFrom().voidItem();
                                })
                                .onFailure().recoverWithUni(failure -> {
                                    LOGGER.error("Error processing station {}: {}",
                                            radioStation.getSlugName(), failure.getMessage());
                                    radioStation.setStatus(RadioStationStatus.SYSTEM_ERROR);
                                    String slug = radioStation.getSlugName();
                                    LOGGER.info("Removing station {} due to system error.", slug);
                                    return radioStationPool.stopAndRemove(slug)
                                            .replaceWithVoid();
                                })
                )
                .merge()
                .toUni()
                .replaceWithVoid();
    }


}