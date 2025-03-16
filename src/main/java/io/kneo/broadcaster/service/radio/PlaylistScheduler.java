package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.service.SoundFragmentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class PlaylistScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistScheduler.class);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, Set<BrandSoundFragment>> recentlyPlayedMap = new ConcurrentHashMap<>();

    @Inject
    private SoundFragmentService soundFragmentService;

    public PlaylistScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            for (String brandName : recentlyPlayedMap.keySet()) {
                soundFragmentService.getBrandSoundFragmentCount(brandName)
                        .subscribe().with(
                                totalCount -> {
                                    if (totalCount != null) {
                                        Set<BrandSoundFragment> played = recentlyPlayedMap.get(brandName);
                                        if (played != null) {
                                            int playedCount = played.size();
                                            double percentagePlayed = (double) playedCount / totalCount * 100;
                                            LOGGER.info("Brand: {}, Played: {}/{} ({}%)",  brandName, playedCount, totalCount, String.format("%.2f", percentagePlayed));
                                            if (percentagePlayed > 90) {
                                                LOGGER.info("Resetting list for brand: {}", brandName);
                                                played.clear();
                                            }
                                        }
                                    }
                                },
                                error -> LOGGER.error("Error counting fragments: {}", error.getMessage())
                        );
            }
        }, 3, 3, TimeUnit.MINUTES);
    }

    public void registerPlaylist(HLSPlaylist playlist) {
        recentlyPlayedMap.put(playlist.getBrandName(), ConcurrentHashMap.newKeySet());
    }

    public void unregisterPlaylist(String brandName) {
        recentlyPlayedMap.remove(brandName);
    }

    public boolean isRecentlyPlayed(String brandName, BrandSoundFragment fragment) {
        Set<BrandSoundFragment> played = recentlyPlayedMap.get(brandName);
        return played != null && played.contains(fragment);
    }

    public void trackPlayedFragment(String brandName, BrandSoundFragment fragment) {
        Set<BrandSoundFragment> played = recentlyPlayedMap.get(brandName);
        if (played != null) {
            played.add(fragment);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        recentlyPlayedMap.clear();
    }
}