package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service responsible for cleaning up old segments from playlists
 */
@ApplicationScoped
public class SegmentsCleaner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentsCleaner.class);
    public static final String SCHEDULED_TASK_ID = "segments-cleanup";
    public static final long INTERVAL_SECONDS = 243;

    @Inject
    private HlsPlaylistConfig hlsConfig;

    @Getter
    private final SchedulerTaskTimeline taskTimeline = new SchedulerTaskTimeline();

    public void initializeTaskTimeline() {
        taskTimeline.registerTask(
                SCHEDULED_TASK_ID,
                "Playlist Cleanup",
                INTERVAL_SECONDS
        );
    }

    public void cleanupPlaylist(HLSPlaylist playlist) {
        if (playlist == null) return;

        taskTimeline.updateProgress();

        try {
            if (playlist.getSegmentCount() > hlsConfig.getMinSegments()) {
                int segmentsToDelete = 50;
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
            LOGGER.error("Error during cleanup for brand {}: {}",
                    playlist.getBrandName(), e.getMessage(), e);
        }
    }
}