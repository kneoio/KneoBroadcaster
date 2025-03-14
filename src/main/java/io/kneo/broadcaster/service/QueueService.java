package io.kneo.broadcaster.service;

import io.kneo.broadcaster.model.InterstitialPlaylistItem;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class QueueService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueService.class);

    private final Map<String, ConcurrentLinkedQueue<InterstitialPlaylistItem>> brandQueues =
            new ConcurrentHashMap<>();

    public ConcurrentLinkedQueue<InterstitialPlaylistItem> getQueueForBrand(String brandName) {
        return brandQueues.computeIfAbsent(brandName, k -> new ConcurrentLinkedQueue<>());
    }

    public Uni<Void> addItem(String brandName, InterstitialPlaylistItem item) {
        return Uni.createFrom().item(() -> {
            if (item != null) {
                getQueueForBrand(brandName).add(item);
                LOGGER.info("Added interstitial to queue for brand {}", brandName);
            }
            return null;
        });
    }

    public Uni<List<InterstitialPlaylistItem>> getQueuedItems(String brandName) {
        return Uni.createFrom().item(() -> {
            return getQueueForBrand(brandName).stream().toList();
        });
    }
}