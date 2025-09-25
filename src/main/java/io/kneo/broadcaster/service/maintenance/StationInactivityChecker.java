package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.util.BrandActivityLogger;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StationInactivityChecker v5
 *
 * Status transitions:
 * - ON_LINE: activity < 5 minutes
 * - WAITING_FOR_CURATOR: inactivity > 10 minutes
 * - IDLE: inactivity > 8 hours
 * - OFF_LINE: 2 hours in IDLE (not whitelisted)
 * - Removed: 1 minute after OFF_LINE
 */
@ApplicationScoped
public class StationInactivityChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(StationInactivityChecker.class);
    private static final int INTERVAL_SECONDS = 60;
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);

    private static final int WAITING_FOR_CURATOR_THRESHOLD_MINUTES = 10;
    private static final int IDLE_THRESHOLD_MINUTES = 480;
    private static final int IDLE_TO_OFFLINE_THRESHOLD_MINUTES = 120;
    private static final int REMOVAL_DELAY_MINUTES = 1;

    private static final Set<RadioStationStatus> ACTIVE_STATUSES = Set.of(
            RadioStationStatus.ON_LINE,
            RadioStationStatus.WARMING_UP,
            RadioStationStatus.QUEUE_SATURATED,
            RadioStationStatus.WAITING_FOR_CURATOR,
            RadioStationStatus.SYSTEM_ERROR,
            RadioStationStatus.IDLE
    );

    @Inject
    RadioStationService radioStationService;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    BroadcasterConfig broadcasterConfig;

    private Cancellable cleanupSubscription;
    private final ConcurrentHashMap<String, Instant> stationsMarkedForRemoval = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> idleStatusTime = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("=== Starting station inactivity checker ===");
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
                .onFailure().retry().withBackOff(Duration.ofSeconds(10), Duration.ofMinutes(5)).indefinitely()
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
            cleanupSubscription = null;
        }
    }

    private Uni<Void> checkStationActivity(Long tick) {
        LOGGER.info("=== Station inactivity checking - Tick: {} ===", tick);
        Instant now = Instant.now();
        Instant waitingForCuratorThreshold = now.minusSeconds(WAITING_FOR_CURATOR_THRESHOLD_MINUTES * 60L);
        Instant idleThreshold = now.minusSeconds(IDLE_THRESHOLD_MINUTES * 60L);
        Instant idleToOfflineThreshold = now.minusSeconds(IDLE_TO_OFFLINE_THRESHOLD_MINUTES * 60L);
        Instant removalThreshold = now.minusSeconds(REMOVAL_DELAY_MINUTES * 60L);

        if (!stationsMarkedForRemoval.isEmpty()) {
            LOGGER.info("Stations marked for removal: {}", stationsMarkedForRemoval.keySet());
        }

        return Multi.createFrom().iterable(stationsMarkedForRemoval.entrySet())
                .onItem().transformToUni(entry -> {
                    String slug = entry.getKey();
                    Instant markedTime = entry.getValue();

                    if (markedTime.isBefore(removalThreshold)) {
                        BrandActivityLogger.logActivity(slug, "remove", "Removing station after %d minutes delay", REMOVAL_DELAY_MINUTES);
                        stationsMarkedForRemoval.remove(slug);
                        idleStatusTime.remove(slug);
                        return radioStationPool.stopAndRemove(slug).replaceWithVoid();
                    } else {
                        LOGGER.info("Station {} not ready for removal yet", slug);
                    }
                    return Uni.createFrom().voidItem();
                })
                .merge()
                .toUni()
                .replaceWithVoid()
                .chain(() -> {
                    Collection<RadioStation> currentOnlineStations = radioStationPool.getOnlineStationsSnapshot();
                    LOGGER.info("After cleanup, there are {} active radio stations.", currentOnlineStations.size());

                    return Multi.createFrom().iterable(currentOnlineStations)
                            .onItem().transformToUni(radioStation -> {
                                String slug = radioStation.getSlugName();
                                RadioStationStatus currentStatus = radioStation.getStatus();

                                return radioStationService.findLastAccessTimeByStationName(radioStation.getSlugName())
                                        .onItem().transformToUni(lastAccessTime -> {
                                            if (lastAccessTime != null) {
                                                Instant lastAccessInstant = lastAccessTime.toInstant();
                                                BrandActivityLogger.logActivity(slug, "access", "Last requested at: %s", lastAccessTime);

                                                boolean hasRecentActivity = lastAccessInstant.isAfter(waitingForCuratorThreshold);
                                                boolean isPastIdleThreshold = lastAccessInstant.isBefore(idleThreshold);
                                                boolean isPastWaitingForCuratorThreshold = lastAccessInstant.isBefore(waitingForCuratorThreshold);

                                                if (hasRecentActivity && currentStatus != RadioStationStatus.OFF_LINE) {
                                                    if (currentStatus != RadioStationStatus.ON_LINE) {
                                                        LOGGER.info("Station {} has recent activity, transitioning to ON_LINE", slug);
                                                        BrandActivityLogger.logActivity(slug, "online", "Station has activity, setting status to ON_LINE");
                                                        radioStation.setStatus(RadioStationStatus.ON_LINE);
                                                        stationsMarkedForRemoval.remove(slug);
                                                        idleStatusTime.remove(slug);
                                                    }
                                                    return Uni.createFrom().voidItem();
                                                }

                                                if (currentStatus == RadioStationStatus.OFF_LINE) {
                                                    LOGGER.info("OFF_LINE station {} awaiting removal", slug);
                                                    return Uni.createFrom().voidItem();
                                                }

                                                if (currentStatus == RadioStationStatus.IDLE) {
                                                    Instant idleStartTime = idleStatusTime.get(slug);
                                                    if (idleStartTime != null && idleStartTime.isBefore(idleToOfflineThreshold)
                                                            && !broadcasterConfig.getStationWhitelist().contains(slug)) {
                                                        BrandActivityLogger.logActivity(slug, "offline", "Idle for %d minutes, setting status to OFF_LINE and marking for removal", IDLE_TO_OFFLINE_THRESHOLD_MINUTES);
                                                        radioStation.setStatus(RadioStationStatus.OFF_LINE);
                                                        stationsMarkedForRemoval.put(slug, now);
                                                        idleStatusTime.remove(slug);
                                                    } else if (broadcasterConfig.getStationWhitelist().contains(slug)) {
                                                        LOGGER.info("Station {} is whitelisted, keeping in IDLE status", slug);
                                                    }
                                                } else if (ACTIVE_STATUSES.contains(currentStatus)) {

                                                    if (isPastIdleThreshold
                                                            && (currentStatus == RadioStationStatus.ON_LINE || currentStatus == RadioStationStatus.WAITING_FOR_CURATOR)) {
                                                        BrandActivityLogger.logActivity(slug, "idle", "Inactive for %d minutes, setting status to IDLE", IDLE_THRESHOLD_MINUTES);
                                                        radioStation.setStatus(RadioStationStatus.IDLE);
                                                        idleStatusTime.put(slug, now);
                                                    } else if (isPastWaitingForCuratorThreshold) {
                                                        BrandActivityLogger.logActivity(slug, "waiting_curator", "Inactive for %d minutes, setting status to WAITING_FOR_CURATOR", WAITING_FOR_CURATOR_THRESHOLD_MINUTES);
                                                        radioStation.setStatus(RadioStationStatus.WAITING_FOR_CURATOR);
                                                        idleStatusTime.remove(slug);
                                                    }
                                                }

                                            } else {
                                                if (ACTIVE_STATUSES.contains(currentStatus)) {
                                                    LOGGER.info("Station {} has no access time, setting to WAITING_FOR_CURATOR (grace period)", slug);
                                                    BrandActivityLogger.logActivity(slug, "waiting_curator", "No last access time recorded, setting status to WAITING_FOR_CURATOR (grace period)");
                                                    radioStation.setStatus(RadioStationStatus.WAITING_FOR_CURATOR);
                                                    idleStatusTime.remove(slug);
                                                }
                                            }
                                            return Uni.createFrom().voidItem();
                                        })
                                        .onFailure().recoverWithUni(failure -> {
                                            LOGGER.error("Error getting stats for station {}: {}", slug, failure.getMessage(), failure);
                                            BrandActivityLogger.logActivity(slug, "error", "Error processing station: %s", failure.getMessage());
                                            return Uni.createFrom().voidItem();
                                        });
                            })
                            .merge()
                            .toUni()
                            .replaceWithVoid();
                });
    }
}
