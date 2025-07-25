package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.quarkus.runtime.ShutdownEvent;
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
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StationInactivityChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(StationInactivityChecker.class);
    private static final int INTERVAL_SECONDS = 300;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(100);
    private static final int IDLE_THRESHOLD_MINUTES = 5;
    private static final int STOP_REMOVE_THRESHOLD_MINUTES = 15;
    private static final int REMOVAL_DELAY_MINUTES = 1;

    @Inject
    RadioStationService radioStationService;

    @Inject
    RadioStationPool radioStationPool;

    private Cancellable cleanupSubscription;
    private final ConcurrentHashMap<String, Instant> stationsMarkedForRemoval = new ConcurrentHashMap<>();

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
        Instant now = Instant.now();
        Instant idleThreshold = now.minusSeconds(IDLE_THRESHOLD_MINUTES * 60L);
        Instant stopThreshold = now.minusSeconds(STOP_REMOVE_THRESHOLD_MINUTES * 60L);
        Instant removalThreshold = now.minusSeconds(REMOVAL_DELAY_MINUTES * 60L);
        Collection<RadioStation> onlineStations = radioStationPool.getOnlineStationsSnapshot();

        LOGGER.info("Currently, there are {} active radio stations.", onlineStations.size());

        // First: Check for stations marked for removal
        return Multi.createFrom().iterable(stationsMarkedForRemoval.entrySet())
                .onItem().transformToUni(entry -> {
                    String slug = entry.getKey();
                    Instant markedTime = entry.getValue();
                    if (markedTime.isBefore(removalThreshold)) {
                        LOGGER.info("Removing station {} after {} minutes delay.", slug, REMOVAL_DELAY_MINUTES);
                        stationsMarkedForRemoval.remove(slug);
                        return radioStationPool.stopAndRemove(slug).replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                })
                .merge()
                .toUni()
                .replaceWithVoid()
                .chain(() -> {
                    // Second: Get fresh snapshot after removals and check activity
                    Collection<RadioStation> currentOnlineStations = radioStationPool.getOnlineStationsSnapshot();
                    LOGGER.info("After cleanup, there are {} active radio stations.", currentOnlineStations.size());
                    return Multi.createFrom().iterable(currentOnlineStations)
                        .onItem().transformToUni(radioStation ->
                                radioStationService.getStats(radioStation.getSlugName())
                                        .onItem().transformToUni(stats -> {
                                            String slug = radioStation.getSlugName();
                                            if (stats != null && stats.getLastAccessTime() != null) {
                                                Instant lastAccessInstant = stats.getLastAccessTime().toInstant();
                                                LOGGER.info("Station '{}' last requested at: {}", slug, stats.getLastAccessTime());
                                                if (lastAccessInstant.isBefore(stopThreshold)) {
                                                    LOGGER.info("Station {} inactive for {} minutes, setting status to OFF_LINE and marking for removal.",
                                                            slug, STOP_REMOVE_THRESHOLD_MINUTES);
                                                    radioStation.setStatus(RadioStationStatus.OFF_LINE);
                                                    stationsMarkedForRemoval.put(slug, now);
                                                } else if (lastAccessInstant.isBefore(idleThreshold)) {
                                                    LOGGER.info("Station {} has been inactive for {} minutes, setting status to IDLE.",
                                                            slug, IDLE_THRESHOLD_MINUTES);
                                                    radioStation.setStatus(RadioStationStatus.IDLE);
                                                } else {
                                                    LOGGER.info("Station {} is active, setting status to ON_LINE.", slug);
                                                    radioStation.setStatus(RadioStationStatus.ON_LINE);
                                                }
                                            } else {
                                                LOGGER.info("Station '{}' has no last access time recorded, setting status to IDLE (grace period).", slug);
                                                radioStation.setStatus(RadioStationStatus.IDLE);
                                            }
                                            return Uni.createFrom().voidItem();
                                        })
                                        .onFailure().recoverWithUni(failure -> {
                                            LOGGER.error("Error processing station {}: {}", radioStation.getSlugName(), failure.getMessage());
                                            //radioStation.setStatus(RadioStationStatus.SYSTEM_ERROR);
                                            String slug = radioStation.getSlugName();
                                            //LOGGER.info("Marking station {} for removal due to system error.", slug);
                                            //stationsMarkedForRemoval.put(slug, now);
                                            return Uni.createFrom().voidItem();
                                        })
                        )
                        .merge()
                        .toUni()
                        .replaceWithVoid();
                });
    }
}