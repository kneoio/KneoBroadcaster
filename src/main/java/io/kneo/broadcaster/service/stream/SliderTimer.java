package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;

@ApplicationScoped
public class SliderTimer {
    private static final int INTERVAL_SECONDS = 180;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(100);

    public Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(Duration.ofSeconds(INTERVAL_SECONDS))
                .onOverflow().drop();
    }
}