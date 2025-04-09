package io.kneo.broadcaster.service.stream;

import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@ApplicationScoped
public class GlobalTickerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalTickerService.class);

    private Cancellable tickerSubscription;
    private final Map<String, Consumer<Long>> tickListeners = new ConcurrentHashMap<>();

    @Inject
    private SegmentFeederTimer timerService;

    @PostConstruct
    public void init() {
        startGlobalTicker();
    }

    private void startGlobalTicker() {
        LOGGER.info("Starting global ticker subscription");
        tickerSubscription = timerService.getTicker().subscribe().with(
                this::notifyListeners,
                error -> LOGGER.error("Global ticker subscription error: {}", error.getMessage())
        );
    }

    private void notifyListeners(long timestamp) {
        tickListeners.forEach((id, listener) -> {
            try {
                listener.accept(timestamp);
            } catch (Exception e) {
                LOGGER.error("Error in ticker listener {}: {}", id, e.getMessage(), e);
            }
        });
    }

    public void registerListener(String listenerId, Consumer<Long> tickListener) {
        LOGGER.info("Registering ticker listener: {}", listenerId);
        tickListeners.put(listenerId, tickListener);
    }

    public void unregisterListener(String listenerId) {
        LOGGER.info("Unregistering ticker listener: {}", listenerId);
        tickListeners.remove(listenerId);
    }

    @PreDestroy
    public void shutdown() {
        LOGGER.info("Shutting down global ticker service");
        if (tickerSubscription != null) {
            tickerSubscription.cancel();
        }
        tickListeners.clear();
    }
}