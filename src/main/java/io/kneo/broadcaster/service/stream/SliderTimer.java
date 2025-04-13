package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;

@ApplicationScoped
public class SliderTimer {
    private static final int INTERVAL_SECONDS = 180; // 3 minutes
    private static final Duration INITIAL_DELAY = Duration.ofMillis(100); // Small initial delay

    public Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY) // Small delay to avoid zero
                .every(Duration.ofSeconds(INTERVAL_SECONDS))
                .onOverflow().drop();
    }
}