package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.RadioStation;
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

@ApplicationScoped
public class StationInactivityChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(StationInactivityChecker.class);
    private static final int INTERVAL_SECONDS = 300;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(100);
    private static final int IDLE_THRESHOLD_MINUTES = 5;
    //TODO now too short , later should be extended
    private static final int STOP_REMOVE_THRESHOLD_MINUTES = 10;
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

    private Cancellable cleanupSubscription;
    private final ConcurrentHashMap<String, Instant> stationsMarkedForRemoval = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("=== Starting station inactivity checker ===");
        LOGGER.info("Configuration - Interval: {}s, Idle threshold: {}min, Stop threshold: {}min, Removal delay: {}min",
                INTERVAL_SECONDS, IDLE_THRESHOLD_MINUTES, STOP_REMOVE_THRESHOLD_MINUTES, REMOVAL_DELAY_MINUTES);
        LOGGER.info("Active statuses: {}", ACTIVE_STATUSES);
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
        LOGGER.info("=== Station inactivity checking - Tick: {} ===", tick);
        Instant now = Instant.now();
        Instant idleThreshold = now.minusSeconds(IDLE_THRESHOLD_MINUTES * 60L);
        Instant stopThreshold = now.minusSeconds(STOP_REMOVE_THRESHOLD_MINUTES * 60L);
        Instant removalThreshold = now.minusSeconds(REMOVAL_DELAY_MINUTES * 60L);

        // Log threshold parameters
        LOGGER.info("Thresholds - Now: {}, Idle: {} ({} min ago), Stop: {} ({} min ago), Removal: {} ({} min ago)",
                now, idleThreshold, IDLE_THRESHOLD_MINUTES, stopThreshold, STOP_REMOVE_THRESHOLD_MINUTES,
                removalThreshold, REMOVAL_DELAY_MINUTES);

        Collection<RadioStation> onlineStations = radioStationPool.getOnlineStationsSnapshot();
        LOGGER.info("Currently, there are {} active radio stations.", onlineStations.size());

        // Log stations marked for removal
        if (!stationsMarkedForRemoval.isEmpty()) {
            LOGGER.info("Stations marked for removal: {}", stationsMarkedForRemoval.keySet());
            stationsMarkedForRemoval.forEach((slug, markedTime) ->
                    LOGGER.info("  {} marked at: {} ({}s ago)", slug, markedTime,
                            Duration.between(markedTime, now).getSeconds()));
        }

        return Multi.createFrom().iterable(stationsMarkedForRemoval.entrySet())
                .onItem().transformToUni(entry -> {
                    String slug = entry.getKey();
                    Instant markedTime = entry.getValue();
                    long secondsSinceMarked = Duration.between(markedTime, now).getSeconds();
                    LOGGER.info("Checking removal for {}: marked {}s ago, threshold {}s",
                            slug, secondsSinceMarked, REMOVAL_DELAY_MINUTES * 60);

                    if (markedTime.isBefore(removalThreshold)) {
                        BrandActivityLogger.logActivity(slug, "remove", "Removing station after %d minutes delay", REMOVAL_DELAY_MINUTES);
                        stationsMarkedForRemoval.remove(slug);
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
                                LOGGER.info("Processing station: {} with status: {}", slug, currentStatus);

                                return radioStationService.getStats(radioStation.getSlugName())
                                        .onItem().transformToUni(stats -> {
                                            LOGGER.info("Retrieved stats for {}: {}", slug, stats != null ? "available" : "null");

                                            if (stats != null && stats.getLastAccessTime() != null) {
                                                Instant lastAccessInstant = stats.getLastAccessTime().toInstant();
                                                long secondsSinceAccess = Duration.between(lastAccessInstant, now).getSeconds();

                                                LOGGER.info("Station {}: Last access: {} ({}s ago), Status: {}",
                                                        slug, stats.getLastAccessTime(), secondsSinceAccess, currentStatus);

                                                BrandActivityLogger.logActivity(slug, "access", "Last requested at: %s", stats.getLastAccessTime());

                                                boolean isActive = ACTIVE_STATUSES.contains(currentStatus);
                                                boolean isPastStopThreshold = lastAccessInstant.isBefore(stopThreshold);
                                                boolean isPastIdleThreshold = lastAccessInstant.isBefore(idleThreshold);

                                                LOGGER.info("Station {}: isActive={}, isPastStopThreshold={}, isPastIdleThreshold={}",
                                                        slug, isActive, isPastStopThreshold, isPastIdleThreshold);

                                                if (isActive) {
                                                    if (isPastStopThreshold) {
                                                        LOGGER.info("Station {} transitioning to OFF_LINE (inactive for {}s)", slug, secondsSinceAccess);
                                                        BrandActivityLogger.logActivity(slug, "offline", "Inactive for %d minutes, setting status to OFF_LINE and marking for removal", STOP_REMOVE_THRESHOLD_MINUTES);
                                                        radioStation.setStatus(RadioStationStatus.OFF_LINE);
                                                        stationsMarkedForRemoval.put(slug, now);
                                                    } else if (isPastIdleThreshold) {
                                                        LOGGER.info("Station {} transitioning to IDLE (inactive for {}s)", slug, secondsSinceAccess);
                                                        BrandActivityLogger.logActivity(slug, "idle", "Inactive for %d minutes, setting status to IDLE", IDLE_THRESHOLD_MINUTES);
                                                        radioStation.setStatus(RadioStationStatus.IDLE);
                                                    } else {
                                                        LOGGER.info("Station {} has recent activity, transitioning to ON_LINE", slug);
                                                        BrandActivityLogger.logActivity(slug, "online", "Station is active, setting status to ON_LINE");
                                                        radioStation.setStatus(RadioStationStatus.ON_LINE);
                                                    }
                                                } else if (currentStatus == RadioStationStatus.OFF_LINE) {
                                                    if (!isPastIdleThreshold) {
                                                        LOGGER.info("OFF_LINE station {} has activity, transitioning to ON_LINE", slug);
                                                        BrandActivityLogger.logActivity(slug, "online", "Station has activity, setting status to ON_LINE");
                                                        radioStation.setStatus(RadioStationStatus.ON_LINE);
                                                        stationsMarkedForRemoval.remove(slug);
                                                    } else {
                                                        LOGGER.info("OFF_LINE station {} still inactive", slug);
                                                    }
                                                }
                                            } else {
                                                LOGGER.warn("Station {}: No stats or last access time - stats={}, lastAccessTime={}",
                                                        slug, stats, stats != null ? stats.getLastAccessTime() : "N/A");

                                                if (ACTIVE_STATUSES.contains(currentStatus)) {
                                                    LOGGER.info("Station {} has no access time, setting to IDLE (grace period)", slug);
                                                    BrandActivityLogger.logActivity(slug, "idle", "No last access time recorded, setting status to IDLE (grace period)");
                                                    radioStation.setStatus(RadioStationStatus.IDLE);
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