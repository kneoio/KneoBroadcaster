package io.kneo.broadcaster.service.filemaintainance;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;

@ApplicationScoped
public class MaintenanceTimer {
    private static final int INTERVAL_SECONDS = 360;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(100);

    public Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(Duration.ofSeconds(INTERVAL_SECONDS))
                .onOverflow().drop();
    }
}

