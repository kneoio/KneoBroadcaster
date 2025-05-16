package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

@ApplicationScoped
public class SliderTimer {

    public int intervalSeconds;
    private final Duration initialDelay;

    @Inject
    public SliderTimer(HlsPlaylistConfig config) {
        intervalSeconds = config.getSegmentDuration();
        initialDelay = Duration.ofMillis(config.getSegmentDuration());
    }

    public Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(initialDelay)
                .every(Duration.ofSeconds(intervalSeconds))
                .onOverflow().drop();
    }
}