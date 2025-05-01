package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.Multi;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

@Getter
@ApplicationScoped
public class FileJanitorTimer {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileJanitorTimer.class);
    private static final int DURATION_SEC = 360; // 6 minutes
    private final Multi<Long> ticker;

    public FileJanitorTimer() {
        this.ticker = createTicker();
        LOGGER.info("FileJanitorTimer initialized with {}s interval", DURATION_SEC);
    }

    private Multi<Long> createTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(Duration.ZERO) // First tick immediately
                .every(Duration.ofSeconds(DURATION_SEC))
                .onOverflow().drop()
                .onSubscription().invoke(sub -> LOGGER.debug("New subscriber connected"))
                .onItem().invoke(tick -> LOGGER.debug("Timer tick #{}", tick));
    }

    @PreDestroy
    void cleanup() {
        LOGGER.info("Shutting down FileJanitorTimer");
    }
}