package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

@ApplicationScoped
public class StationActivityCheckerTimer {
    public static final int INTERVAL_MINUTES = 30;
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(15);

    public Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(Duration.ofMinutes(INTERVAL_MINUTES))
                .onOverflow().drop();
    }
}