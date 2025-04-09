package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BroadcastingStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

@ApplicationScoped
public class RadioStationPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationPool.class);

    @Getter
    @Setter
    private HashMap<String, RadioStation> pool = new HashMap<>();

    @Inject
    private RadioStationService radioStationService;

    @Inject
    private HlsPlaylistConfig config;

    @Inject
    private SoundFragmentService soundFragmentService;

    @Inject
    private SegmentFeederTimer timerService;

    @Inject
    private WindowSliderTimer windowSliderService;

    @Inject
    AudioSegmentationService segmentationService;

    public Uni<RadioStation> initializeStation(String brandName) {
        HLSPlaylist playlist = new HLSPlaylist(
                timerService,
                windowSliderService,
                config,
                soundFragmentService,
                segmentationService,
                brandName
        );
        playlist.initialize();

        return radioStationService.findByBrandName(brandName)
                .onItem().invoke(station -> {
                    station.setPlaylist(playlist);
                    station.setStatus(RadioStationStatus.WARMING_UP);
                    playlist.setRadioStation(station);
                    pool.put(brandName, station);
                })
                .onItem().transformToUni(station -> Uni.createFrom().item(station));
    }

    public Uni<RadioStation> get(String brandName) {
        RadioStation radioStation = pool.get(brandName);
        return Uni.createFrom().item(radioStation);
    }

    public Uni<RadioStation> stop(String brandName) {
        RadioStation radioStation = pool.get(brandName);
        HLSPlaylist playlist = radioStation.getPlaylist();
        playlist.shutdown();
        radioStation.setStatus(RadioStationStatus.OFF_LINE);
        return Uni.createFrom().item(radioStation);
    }

    public Uni<BroadcastingStats> checkStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        RadioStation radioStation = pool.get(name);
        if (radioStation != null) {
            stats.setStatus(radioStation.getStatus());
        }
        return Uni.createFrom().item(stats);
    }
}