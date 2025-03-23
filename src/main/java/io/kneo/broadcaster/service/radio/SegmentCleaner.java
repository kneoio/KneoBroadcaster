package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class SegmentCleaner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentCleaner.class);
    public static final String SCHEDULED_TASK_ID = "segments-cleanup";
    public static final long INTERVAL_SECONDS = 2430;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    @Inject
    private HlsPlaylistConfig hlsConfig;

    @Getter
    private final SchedulerTaskTimeline taskTimeline = new SchedulerTaskTimeline();

    private final Map<String, HLSPlaylist> registeredPlaylists = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        taskTimeline.registerTask(
                SCHEDULED_TASK_ID,
                "Segment Cleanup",
                INTERVAL_SECONDS
        );
        scheduler.scheduleAtFixedRate(() -> {
            try {
                taskTimeline.updateProgress();
                cleanupAllPlaylists();
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void registerPlaylist(HLSPlaylist playlist) {
        if (playlist == null) return;

        String playlistId = playlist.getBrandName();
        registeredPlaylists.put(playlistId, playlist);
        LOGGER.info("Registered playlist for cleanup: {}", playlistId);
    }

    public void unregisterPlaylist(String playlistId) {
        if (registeredPlaylists.remove(playlistId) != null) {
            LOGGER.info("Unregistered playlist from cleanup: {}", playlistId);
        }
    }

    private void cleanupAllPlaylists() {
        LOGGER.info("Starting cleanup of {} registered playlists", registeredPlaylists.size());
        registeredPlaylists.values().forEach(this::cleanupPlaylist);
    }

    public void cleanupPlaylist(HLSPlaylist playlist) {
        if (playlist == null) return;

        taskTimeline.updateProgress();

        try {
            if (playlist.getSegmentCount() >= hlsConfig.getMaxSegments()) {
                int segmentsToDelete = hlsConfig.getMaxSegments() / 3;
                int currentSize = playlist.getSegmentCount();

                Long oldestToKeep = playlist.getSegmentKeys().stream()
                        .skip(segmentsToDelete)
                        .findFirst()
                        .orElse(null);

                if (oldestToKeep != null) {
                    playlist.removeSegmentsBefore(oldestToKeep);
                    LOGGER.info("Cleaned segments for brand {}: before={}, after={}",
                            playlist.getBrandName(), currentSize, playlist.getSegmentCount());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error during segment cleanup for brand {}: {}", playlist.getBrandName(), e.getMessage(), e);
        }
    }
}