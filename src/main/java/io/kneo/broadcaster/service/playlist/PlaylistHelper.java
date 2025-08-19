package io.kneo.broadcaster.service.playlist;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PlaylistHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistHelper.class);

    private final Map<String, PlaylistTracker> brandTrackers = new ConcurrentHashMap<>();
    private static final int DEFAULT_MAX_TRACKED_SONGS = 1000;
    private static final long DEFAULT_RESET_HOURS = 24;
    private static final double DEFAULT_RESET_THRESHOLD = 0.8; // 80%

    public PlaylistTracker getTracker(String brandName) {
        return brandTrackers.computeIfAbsent(brandName,
                k -> new PlaylistTracker(DEFAULT_MAX_TRACKED_SONGS, DEFAULT_RESET_HOURS));
    }

    public void resetTracker(String brandName) {
        PlaylistTracker tracker = brandTrackers.get(brandName);
        if (tracker != null) {
            tracker.reset();
            tracker.logActivity(brandName, "manual_tracker_reset",
                    "Playlist tracker manually reset");
        }
    }

    public int getPlayedCount(String brandName) {
        PlaylistTracker tracker = brandTrackers.get(brandName);
        return tracker != null ? tracker.getPlayedCount() : 0;
    }

    public boolean shouldResetTracker(String brandName, int totalAvailableSongs) {
        PlaylistTracker tracker = brandTrackers.get(brandName);
        if (tracker == null) return false;

        // Check time-based reset
        if (tracker.shouldReset()) {
            tracker.logActivity(brandName, "time_based_reset",
                    "Tracker needs reset after %d hours", DEFAULT_RESET_HOURS);
            return true;
        }

        // Check threshold-based reset
        if (tracker.needsReset(totalAvailableSongs, DEFAULT_RESET_THRESHOLD)) {
            tracker.logActivity(brandName, "threshold_based_reset",
                    "Tracker needs reset: %d played out of %d total (%.1f%%)",
                    tracker.getPlayedCount(), totalAvailableSongs,
                    (double) tracker.getPlayedCount() / totalAvailableSongs * 100);
            return true;
        }

        return false;
    }

    public void cleanupOldTrackers() {
        brandTrackers.entrySet().removeIf(entry -> {
            PlaylistTracker tracker = entry.getValue();
            boolean isOld = tracker.getLastReset().plusDays(7).isBefore(java.time.LocalDateTime.now());
            if (isOld) {
                LOGGER.debug("Removing old tracker for brand: {}", entry.getKey());
            }
            return isOld;
        });
    }
}