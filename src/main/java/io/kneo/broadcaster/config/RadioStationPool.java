package io.kneo.broadcaster.config;

import io.kneo.broadcaster.controller.stream.Playlist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BroadcastingStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.exceptions.PlaylistException;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Random;

@Singleton
public class RadioStationPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationPool.class);
    @Setter
    private HashMap<String, RadioStation> pool = new HashMap<>();

    @Inject
    private RadioStationService radioStationService;

    @Inject
    private SoundFragmentService soundFragmentService;

    @Inject
    private HlsPlaylistConfig config;

    public Uni<RadioStation> get(String name) {
        Playlist playlist = new Playlist(config);
        RadioStation radioStation = pool.get(name);

        if (radioStation == null || radioStation.getPlaylist().getSegmentCount() == 0) {
            LOGGER.info("Starting radio station: {}", name);
            return soundFragmentService.getAll(0, 100)
                    .onItem().transformToUni(fragments -> {
                        return radioStationService.findByBrandName(name).onItem().transform(station -> {
                            List<SoundFragment> randomFragments = getRandomFragments(fragments, 3);
                            for (SoundFragment fragment : randomFragments) {
                                playlist.addSegment(fragment.getFile());
                            }
                            if (playlist.getSegmentCount() == 0) {
                                throw new PlaylistException("Playlist is empty");
                            }
                            station.setPlaylist(playlist);
                            station.setStatus(RadioStationStatus.ON_LINE);
                            pool.put(name, station);
                            return station;
                        });
                    })
                    .onFailure().invoke(failure -> {
                        LOGGER.error("Failed to initialize radio station: {}", name, failure);
                    })
                    .onItem().invoke(station -> {
                        LOGGER.info("Initialized radio station: {}", name);
                    });
        }
        return Uni.createFrom().item(radioStation);

    }

    public Uni<BroadcastingStats> checkStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        RadioStation radioStation = pool.get(name);
        if (radioStation != null) {
            stats.setStatus(radioStation.getStatus());
            Playlist playlist = radioStation.getPlaylist();
            if (playlist != null) {
                stats.setSegmentsCount(playlist.getSegmentCount());
            }
        }
        return Uni.createFrom().item(stats);
    }

    private List<SoundFragment> getRandomFragments(List<SoundFragment> fragments, int count) {
        Random random = new Random();
        return random.ints(0, fragments.size())
                .distinct()
                .limit(count)
                .mapToObj(fragments::get)
                .toList();
    }


}
