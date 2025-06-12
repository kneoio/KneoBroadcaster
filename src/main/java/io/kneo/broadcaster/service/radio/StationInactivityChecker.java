package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.quarkus.runtime.StartupEvent;
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
    RadioStationService statsService;

    @Inject
    RadioStationPool radioStationPool;

    private Cancellable cleanupSubscription;

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Starting station inactivity checker.");
        startCleanupTask();
    }

    private void startCleanupTask() {
        cleanupSubscription = getTicker()
                .onItem().call(this::checkStationActivity)
                .onFailure().invoke(error -> LOGGER.error("Timer error", error))
                .subscribe().with(
                        item -> {},
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
        }
    }

    private Uni<Void> checkStationActivity(Long tick) {
        LOGGER.info("Station inactivity checking...");
        OffsetDateTime tenMinutesAgo = OffsetDateTime.now().minusMinutes(IDLE_THRESHOLD_MINUTES);
        OffsetDateTime fifteenMinutesAgo = OffsetDateTime.now().minusMinutes(STOP_REMOVE_THRESHOLD_MINUTES);
        Collection<RadioStation> onlineStations = radioStationPool.getOnlineStationsSnapshot();

        LOGGER.info("Currently, there are {} active radio stations.", onlineStations.size());

        // Log the last request time for each active station
        for (RadioStation station : onlineStations) {
            statsService.getStats(station.getSlugName())
                    .onItem().ifNotNull().invoke(stats -> {
                        if (stats.getLastAccessTime() != null) {
                            LOGGER.info("Station '{}' (ID: {}) last requested at: {}",
                                    station.getSlugName(), station.getId(), stats.getLastAccessTime());
                        } else {
                            LOGGER.info("Station '{}' (ID: {}) has no last access time recorded.",
                                    station.getSlugName(), station.getId());
                        }
                    })
                    .onFailure().invoke(failure ->
                            LOGGER.warn("Could not retrieve stats for station '{}' to log last access time: {}",
                                    station.getSlugName(), failure.getMessage()))
                    .subscribe().with(
                            item -> {},
                            failure -> {} // Already handled in onFailure
                    );
        }

        return Multi.createFrom().iterable(onlineStations)
                .onItem().transformToUni(radioStation ->
                        statsService.getStats(radioStation.getSlugName())
                                .onItem().transformToUni(stats -> {
                                    if (stats != null && stats.getLastAccessTime() != null) {
                                        Instant lastAccessInstant = stats.getLastAccessTime().toInstant();
                                        if (lastAccessInstant.isBefore(tenMinutesAgo.toInstant())) {
                                            LOGGER.info("Station {} has been inactive for {} minutes, setting status to IDLE.",
                                                    radioStation.getSlugName(), IDLE_THRESHOLD_MINUTES);
                                            radioStation.setStatus(RadioStationStatus.IDLE);
                                        }

                                        if (lastAccessInstant.isBefore(fifteenMinutesAgo.toInstant())) {
                                            LOGGER.info("Station {} inactive for {} minutes, stopping and removing.",
                                                    radioStation.getSlugName(), STOP_REMOVE_THRESHOLD_MINUTES);
                                            return radioStationPool.stopAndRemove(radioStation.getSlugName())
                                                    .replaceWithVoid();
                                        }
                                    }
                                    return Uni.createFrom().voidItem();
                                })
                                .onFailure().invoke(failure ->
                                        LOGGER.error("Error processing station {}", radioStation.getSlugName(), failure)
                                )
                )
                .merge()
                .toUni()
                .replaceWithVoid();
    }
}