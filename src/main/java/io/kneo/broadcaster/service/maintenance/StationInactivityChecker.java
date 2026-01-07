package io.kneo.broadcaster.service.maintenance;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.util.BrandLogger;
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
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);

    private static final int IDLE_THRESHOLD_MINUTES = 5;
    private static final int IDLE_TO_OFFLINE_THRESHOLD_MINUTES = 120;
    private static final int REMOVAL_DELAY_MINUTES = 1;

    private static final Set<StreamStatus> ACTIVE_STATUSES = Set.of(
            StreamStatus.ON_LINE,
            StreamStatus.WARMING_UP,
            StreamStatus.QUEUE_SATURATED,
            StreamStatus.SYSTEM_ERROR
    );

    private static final Set<StreamStatus> TERMINAL_STATUSES = Set.of(
            StreamStatus.OFF_LINE,
            StreamStatus.FINISHED
    );

    @Inject
    BrandService brandService;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    BroadcasterConfig broadcasterConfig;

    private Cancellable cleanupSubscription;
    private final ConcurrentHashMap<String, Instant> stationsMarkedForRemoval = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> idleStatusTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> stationStartTime = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("=== Starting station inactivity checker ===");
        stopCleanupTask();
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
                .subscribe().with(item -> {}, failure -> LOGGER.error("Subscription failed", failure));
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
        Instant now = Instant.now();
        Instant idleThreshold = now.minusSeconds(IDLE_THRESHOLD_MINUTES * 60L);
        Instant idleToOfflineThreshold = now.minusSeconds(IDLE_TO_OFFLINE_THRESHOLD_MINUTES * 60L);
        Instant removalThreshold = now.minusSeconds(REMOVAL_DELAY_MINUTES * 60L);

        return Multi.createFrom().iterable(stationsMarkedForRemoval.entrySet())
                .onItem().transformToUni(entry -> {
                    String slug = entry.getKey();
                    Instant markedTime = entry.getValue();
                    if (markedTime.isBefore(removalThreshold)) {
                        BrandLogger.logActivity(slug, "remove", "Removing station after %d minutes delay", REMOVAL_DELAY_MINUTES);
                        stationsMarkedForRemoval.remove(slug);
                        idleStatusTime.remove(slug);
                        stationStartTime.remove(slug);
                        return radioStationPool.stopAndRemove(slug).replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                })
                .merge()
                .toUni()
                .replaceWithVoid()
                .chain(() -> {
                    Collection<IStream> currentOnlineStations = radioStationPool.getOnlineStationsSnapshot();

                    return Multi.createFrom().iterable(currentOnlineStations)
                            .onItem().transformToUni(radioStation -> {
                                String slug = radioStation.getSlugName();
                                StreamStatus currentStatus = radioStation.getStatus();

                                return brandService.findLastAccessTimeByStationName(slug)
                                        .onItem().transformToUni(lastAccessTime -> {
                                            if (lastAccessTime != null) {
                                                Instant lastAccessInstant = lastAccessTime.toInstant();
                                                boolean isPastIdleThreshold = lastAccessInstant.isBefore(idleThreshold);

                                                if (!isPastIdleThreshold && currentStatus != StreamStatus.OFF_LINE) {
                                                    if (currentStatus != StreamStatus.ON_LINE) {
                                                        radioStation.setStatus(StreamStatus.ON_LINE);
                                                        stationsMarkedForRemoval.remove(slug);
                                                        idleStatusTime.remove(slug);
                                                    }
                                                    return Uni.createFrom().voidItem();
                                                }

                                                if (TERMINAL_STATUSES.contains(currentStatus))
                                                    return Uni.createFrom().voidItem();

                                                if (currentStatus == StreamStatus.IDLE) {
                                                    Instant idleStartTime = idleStatusTime.get(slug);
                                                    if (idleStartTime != null && idleStartTime.isBefore(idleToOfflineThreshold)
                                                            && !broadcasterConfig.getStationWhitelist().contains(slug)) {
                                                        radioStation.setStatus(StreamStatus.OFF_LINE);
                                                        stationsMarkedForRemoval.put(slug, now);
                                                        idleStatusTime.remove(slug);
                                                        stationStartTime.remove(slug);
                                                    }
                                                } else if (ACTIVE_STATUSES.contains(currentStatus)) {
                                                    if (isPastIdleThreshold) {
                                                        radioStation.setStatus(StreamStatus.IDLE);
                                                        idleStatusTime.put(slug, now);
                                                    }
                                                } else if (currentStatus == StreamStatus.FINISHED && radioStation instanceof OneTimeStream) {
                                                    // Handle finished OneTimeStream - mark for removal after 1 minute
                                                    stationsMarkedForRemoval.put(slug, now);
                                                    idleStatusTime.remove(slug);
                                                    stationStartTime.remove(slug);
                                                }
                                            } else {
                                                stationStartTime.putIfAbsent(slug, now);
                                                Instant startTime = stationStartTime.get(slug);
                                                boolean hasBeenRunning5Min = startTime.isBefore(idleThreshold);
                                                
                                                if (ACTIVE_STATUSES.contains(currentStatus) && hasBeenRunning5Min) {
                                                    radioStation.setStatus(StreamStatus.IDLE);
                                                    idleStatusTime.put(slug, now);
                                                }
                                                
                                                if (currentStatus == StreamStatus.IDLE) {
                                                    Instant idleStartTime = idleStatusTime.get(slug);
                                                    if (idleStartTime != null && idleStartTime.isBefore(idleToOfflineThreshold)
                                                            && !broadcasterConfig.getStationWhitelist().contains(slug)) {
                                                        radioStation.setStatus(StreamStatus.OFF_LINE);
                                                        stationsMarkedForRemoval.put(slug, now);
                                                        idleStatusTime.remove(slug);
                                                        stationStartTime.remove(slug);
                                                    }
                                                }
                                            }
                                            return Uni.createFrom().voidItem();
                                        })
                                        .onFailure().recoverWithUni(failure -> Uni.createFrom().voidItem());
                            })
                            .merge()
                            .toUni()
                            .replaceWithVoid();
                });
    }
}
