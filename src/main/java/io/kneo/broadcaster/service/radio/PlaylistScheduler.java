package io.kneo.broadcaster.service.radio;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.stats.SchedulerTaskTimeline;
import io.kneo.broadcaster.service.SoundFragmentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class PlaylistScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistScheduler.class);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, Set<BrandSoundFragment>> recentlyPlayedMap = new ConcurrentHashMap<>();

    /**
     * -- GETTER --
     *  Get task timeline for dashboard UI
     */
    // Timeline view for real-time task progress
    @Getter
    private final SchedulerTaskTimeline taskTimeline = new SchedulerTaskTimeline();
    private static final String MAINTENANCE_TASK_ID = "playlist-maintenance";
    private static final String JANITOR_TASK_ID = "playlist-janitor";
    private static final long MAINTENANCE_INTERVAL_SECONDS = 180; // 3 minutes
    private static final long JANITOR_INTERVAL_SECONDS = 240; // 4 minutes

    @Inject
    private SoundFragmentService soundFragmentService;

    public PlaylistScheduler() {
        // Register scheduled tasks in the timeline
        taskTimeline.registerTask(
                MAINTENANCE_TASK_ID,
                "Playlist Maintenance",
                "PlaylistScheduler",
                MAINTENANCE_INTERVAL_SECONDS
        );

        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Record task execution in the timeline
                taskTimeline.recordTaskExecution(MAINTENANCE_TASK_ID);

                for (String brandName : recentlyPlayedMap.keySet()) {
                    soundFragmentService.getBrandSoundFragmentCount(brandName)
                            .subscribe().with(
                                    totalCount -> {
                                        if (totalCount != null) {
                                            Set<BrandSoundFragment> played = recentlyPlayedMap.get(brandName);
                                            if (played != null) {
                                                int playedCount = played.size();
                                                double percentagePlayed = (double) playedCount / totalCount * 100;

                                                LOGGER.info("Brand: {}, Played: {}/{} ({}%)",
                                                        brandName, playedCount, totalCount,
                                                        String.format("%.2f", percentagePlayed));

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
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, MAINTENANCE_INTERVAL_SECONDS, TimeUnit.SECONDS);
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

    public List<String> getRecentlyPlayedTitles(String brandName, int limit) {
        if (!recentlyPlayedMap.containsKey(brandName)) {
            return Collections.emptyList();
        }

        return recentlyPlayedMap.get(brandName).stream()
                .limit(limit)
                .map(fragment -> fragment.getSoundFragment().getTitle())
                .collect(Collectors.toList());
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