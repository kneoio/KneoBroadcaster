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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class PlaylistKeeper {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistKeeper.class);
    private final Map<String, Set<BrandSoundFragment>> recentlyPlayedMap = new ConcurrentHashMap<>();

    @Getter
    private final SchedulerTaskTimeline taskTimeline = new SchedulerTaskTimeline();
    private static final String SCHEDULED_TASK_ID = "playlist-keeper";
    private static final long INTERVAL_SECONDS = 240;

    @Inject
    private SoundFragmentService soundFragmentService;

    public PlaylistKeeper() {
        taskTimeline.registerTask(
                SCHEDULED_TASK_ID,
                "Playlist Keeper",
                INTERVAL_SECONDS
        );

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                taskTimeline.updateProgress();

                for (String brandName : recentlyPlayedMap.keySet()) {
                    soundFragmentService.getBrandSoundFragmentCount(brandName)
                            .subscribe().with(
                                    totalCount -> {
                                        Set<BrandSoundFragment> played = recentlyPlayedMap.get(brandName);
                                        int playedCount = played.size();
                                        double percentagePlayed = (double) playedCount / totalCount * 100;
                                        LOGGER.info("Brand: {}, Played: {}/{} ({}%)", brandName, playedCount, totalCount, String.format("%.2f", percentagePlayed));
                                        if (percentagePlayed > 10) {
                                            LOGGER.info("Resetting list for brand: {}", brandName);
                                            played.clear();
                                        }
                                    },
                                    error -> LOGGER.error("Error counting fragments: {}", error.getMessage())
                            );
                }
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: {}", e.getMessage(), e);
            }
        }, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void registerPlaylist(HLSPlaylist playlist) {
        recentlyPlayedMap.put(playlist.getBrandName(), ConcurrentHashMap.newKeySet());
    }

    public void unregisterPlaylist(String brandName) {
        recentlyPlayedMap.remove(brandName);
    }

    public boolean notInPlaylist(String brandName, BrandSoundFragment fragment) {
        Set<BrandSoundFragment> played = recentlyPlayedMap.get(brandName);
        if (played == null) {
            return true;
        }
        return !played.contains(fragment);
    }

    public void trackPlayedFragment(String brandName, BrandSoundFragment fragment) {
        // Add to the recentlyPlayedMap set
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

}