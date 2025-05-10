package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

@ApplicationScoped
public class StationActivityCheckerTimer {
    public static final int INTERVAL_MINUTES = 5;
    //TODO adjust in real prod
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(0);

    public Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(Duration.ofMinutes(INTERVAL_MINUTES))
                .onOverflow().drop();
    }
}