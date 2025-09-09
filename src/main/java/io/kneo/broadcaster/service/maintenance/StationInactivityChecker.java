package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.config.BroadcasterConfig;
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
    private static final int INTERVAL_SECONDS = 60;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(50);

    private static final int WAITING_FOR_CURATOR_THRESHOLD_MINUTES = 5;
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
        LOGGER.info("Configuration - Interval: {}s, Waiting for curator: {}min, Idle: {}min, Idle to offline: {}min, Removal delay: {}min",
                INTERVAL_SECONDS, WAITING_FOR_CURATOR_THRESHOLD_MINUTES, IDLE_THRESHOLD_MINUTES, IDLE_TO_OFFLINE_THRESHOLD_MINUTES, REMOVAL_DELAY_MINUTES);
        LOGGER.info("Active statuses: {}", ACTIVE_STATUSES);
        LOGGER.info("Whitelisted stations (never go offline): {}", broadcasterConfig.getStationWhitelist());
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

        LOGGER.info("Thresholds - Now: {}, WaitingForCurator: {} ({} min ago), Idle: {} ({} min ago), IdleToOffline: {} ({} min ago), Removal: {} ({} min ago)",
                now, waitingForCuratorThreshold, WAITING_FOR_CURATOR_THRESHOLD_MINUTES,
                idleThreshold, IDLE_THRESHOLD_MINUTES, idleToOfflineThreshold, IDLE_TO_OFFLINE_THRESHOLD_MINUTES,
                removalThreshold, REMOVAL_DELAY_MINUTES);

        Collection<RadioStation> onlineStations = radioStationPool.getOnlineStationsSnapshot();
        LOGGER.info("Currently, there are {} active radio stations.", onlineStations.size());

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

                                                boolean hasRecentActivity = lastAccessInstant.isAfter(waitingForCuratorThreshold);
                                                boolean isPastIdleThreshold = lastAccessInstant.isBefore(idleThreshold);

                                                LOGGER.info("Station {}: hasRecentActivity={}, isPastIdleThreshold={}",
                                                        slug, hasRecentActivity, isPastIdleThreshold);

                                                // If activity within 10 minutes, set to ON_LINE (except OFF_LINE which needs separate handling)
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

                                                // Handle OFF_LINE stations with recent activity
                                                if (currentStatus == RadioStationStatus.OFF_LINE) {
                                                    if (hasRecentActivity) {
                                                        LOGGER.info("OFF_LINE station {} has activity, transitioning to ON_LINE", slug);
                                                        BrandActivityLogger.logActivity(slug, "online", "Station has activity, setting status to ON_LINE");
                                                        radioStation.setStatus(RadioStationStatus.ON_LINE);
                                                        stationsMarkedForRemoval.remove(slug);
                                                        idleStatusTime.remove(slug);
                                                    } else {
                                                        LOGGER.info("OFF_LINE station {} still inactive", slug);
                                                    }
                                                    return Uni.createFrom().voidItem();
                                                }

                                                // Handle transitions for inactive stations
                                                if (currentStatus == RadioStationStatus.IDLE) {
                                                    Instant idleStartTime = idleStatusTime.get(slug);
                                                    if (idleStartTime != null && idleStartTime.isBefore(idleToOfflineThreshold)) {
                                                        LOGGER.info("Station {} transitioning from IDLE to OFF_LINE (idle for {}s)", slug,
                                                                Duration.between(idleStartTime, now).getSeconds());
                                                        BrandActivityLogger.logActivity(slug, "offline", "Idle for %d minutes, setting status to OFF_LINE and marking for removal", IDLE_TO_OFFLINE_THRESHOLD_MINUTES);
                                                        radioStation.setStatus(RadioStationStatus.OFF_LINE);
                                                        stationsMarkedForRemoval.put(slug, now);
                                                        idleStatusTime.remove(slug);
                                                    }
                                                } else if (ACTIVE_STATUSES.contains(currentStatus)) {
                                                    if (isPastIdleThreshold) {
                                                        LOGGER.info("Station {} transitioning to IDLE (inactive for {}s)", slug, secondsSinceAccess);
                                                        BrandActivityLogger.logActivity(slug, "idle", "Inactive for %d minutes, setting status to IDLE", IDLE_THRESHOLD_MINUTES);
                                                        radioStation.setStatus(RadioStationStatus.IDLE);
                                                        idleStatusTime.put(slug, now);
                                                    } else {
                                                        LOGGER.info("Station {} transitioning to WAITING_FOR_CURATOR (inactive for {}s)", slug, secondsSinceAccess);
                                                        BrandActivityLogger.logActivity(slug, "waiting_curator", "Inactive for %d minutes, setting status to WAITING_FOR_CURATOR", WAITING_FOR_CURATOR_THRESHOLD_MINUTES);
                                                        radioStation.setStatus(RadioStationStatus.WAITING_FOR_CURATOR);
                                                        idleStatusTime.remove(slug);
                                                    }
                                                }
                                            } else {
                                                LOGGER.warn("Station {}: No stats or last access time - stats={}, lastAccessTime={}",
                                                        slug, stats, stats != null ? stats.getLastAccessTime() : "N/A");

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