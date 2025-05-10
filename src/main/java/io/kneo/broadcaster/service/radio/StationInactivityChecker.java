package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.StationActivityCheckerTimer;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;

@ApplicationScoped
public class StationInactivityChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(StationInactivityChecker.class);

    @Inject
    RadioStationService statsService;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    StationActivityCheckerTimer activityCheckerTimer;

    private Cancellable tickerSubscription;

    @PostConstruct
    public void init() {
        tickerSubscription = activityCheckerTimer.getTicker()
                .onItem().call(() -> {
                    OffsetDateTime thirtyMinutesAgo = OffsetDateTime.now().minusMinutes(15);
                    Collection<RadioStation> onlineStations = radioStationPool.getOnlineStationsSnapshot();

                    return Multi.createFrom().iterable(onlineStations)
                            .onItem().transformToUni(radioStation ->
                                    statsService.getStats(radioStation.getSlugName())
                                            .onItem().transformToUni(stats -> {
                                                if (stats != null && stats.getLastAccessTime() != null) {
                                                    Instant lastAccessInstant = stats.getLastAccessTime().toInstant();
                                                    Instant thresholdInstant = thirtyMinutesAgo.toInstant();

                                                    if (lastAccessInstant.isBefore(thresholdInstant)) {
                                                        LOGGER.info("Station {} inactive, stopping and removing.", radioStation.getSlugName());
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
                })
                .subscribe().with(
                        tick -> {},
                        failure -> LOGGER.error("Ticker failed", failure)
                );
    }

    @PreDestroy
    public void destroy() {
        if (tickerSubscription != null) {
            tickerSubscription.cancel();
        }
    }
}