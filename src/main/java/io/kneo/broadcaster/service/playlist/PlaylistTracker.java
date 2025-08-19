package io.kneo.broadcaster.service.playlist;

import io.kneo.broadcaster.util.BrandActivityLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class PlaylistTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistTracker.class);

    private final Set<UUID> playedSongIds = new HashSet<>();
    private LocalDateTime lastReset = LocalDateTime.now();
    private final int maxTrackedSongs;
    private final long resetHours;

    public PlaylistTracker(int maxTrackedSongs, long resetHours) {
        this.maxTrackedSongs = maxTrackedSongs;
        this.resetHours = resetHours;
    }

    public PlaylistTracker() {
        this(1000, 24); // Default values
    }

    public boolean hasPlayed(UUID songId) {
        return playedSongIds.contains(songId);
    }

    public void markAsPlayed(UUID songId) {
        playedSongIds.add(songId);

        // Prevent memory leaks by limiting size
        if (playedSongIds.size() > maxTrackedSongs) {
            Set<UUID> recentSongs = playedSongIds.stream()
                    .skip(playedSongIds.size() - maxTrackedSongs / 2)
                    .collect(Collectors.toSet());
            playedSongIds.clear();
            playedSongIds.addAll(recentSongs);

            LOGGER.debug("Trimmed playlist tracker to {} songs", recentSongs.size());
        }
    }

    public boolean shouldReset() {
        return LocalDateTime.now().isAfter(lastReset.plusHours(resetHours));
    }

    public void reset() {
        playedSongIds.clear();
        lastReset = LocalDateTime.now();
        LOGGER.debug("Playlist tracker reset, cleared {} played songs", playedSongIds.size());
    }

    public int getPlayedCount() {
        return playedSongIds.size();
    }

    public LocalDateTime getLastReset() {
        return lastReset;
    }

    public boolean needsReset(int totalAvailableSongs, double resetThreshold) {
        if (totalAvailableSongs == 0) return false;

        // Reset if we've played all songs
        if (playedSongIds.size() >= totalAvailableSongs) {
            return true;
        }

        // Reset if we've exceeded the threshold percentage
        double playedPercentage = (double) playedSongIds.size() / totalAvailableSongs;
        return playedPercentage > resetThreshold;
    }

    public void logActivity(String brandName, String activity, String message, Object... args) {
        BrandActivityLogger.logActivity(brandName, activity, message, args);
    }
}