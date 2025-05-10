package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BroadcastingStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RadioStationPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationPool.class);

   private final ConcurrentHashMap<String, RadioStation> pool = new ConcurrentHashMap<>();

    @Inject
    private RadioStationService radioStationService;

    @Inject
    private HlsPlaylistConfig config;

    @Inject
    private SoundFragmentService soundFragmentService;

    @Inject
    private SliderTimer sliderTimer;

    @Inject
    private SegmentFeederTimer feederTimer;

    @Inject
    private SegmentJanitorTimer janitorTimer;

    @Inject
    AudioSegmentationService segmentationService;

    public Uni<RadioStation> initializeStation(String brandName) {
        HLSPlaylist playlist = new HLSPlaylist(
                sliderTimer,
                feederTimer,
                janitorTimer,
                config,
                soundFragmentService,
                segmentationService,
                3
        );

        return radioStationService.findByBrandName(brandName)
                .onItem().invoke(station -> {
                    station.setPlaylist(playlist);
                    if (station.getManagedBy() == ManagedBy.ITSELF || station.getManagedBy() == ManagedBy.MIX) {
                        station.setStatus(RadioStationStatus.WARMING_UP);
                    } else {
                        station.setStatus(RadioStationStatus.WAITING_FOR_CURATOR);
                    }
                    playlist.setRadioStation(station);
                    playlist.initialize();
                    pool.put(brandName, station);
                })
                .onItem().transformToUni(station -> Uni.createFrom().item(station));
    }

    public Uni<RadioStation> get(String brandName) {
        RadioStation radioStation = pool.get(brandName);
        return Uni.createFrom().item(radioStation);
    }

    public Uni<RadioStation> stopAndRemove(String slugName) {
        return stop(slugName)
                .onItem().invoke(stoppedStation -> {
                    if (stoppedStation != null) {
                        pool.remove(slugName);
                    }
                });
    }

    public Collection<RadioStation> getOnlineStationsSnapshot() {
        return new ArrayList<>(pool.values());
    }

    public Optional<RadioStation> getStation(String slugName) {
        return Optional.ofNullable(pool.get(slugName));
    }

    public Uni<RadioStation> stop(String brandName) {
        RadioStation radioStation = pool.get(brandName);
        if (radioStation == null) {
            LOGGER.warn("Attempted to stop non-existent station: {}", brandName);
            return Uni.createFrom().nullItem();
        }
        HLSPlaylist playlist = radioStation.getPlaylist();
        if (playlist != null) {
            playlist.shutdown();
        }
        radioStation.setStatus(RadioStationStatus.OFF_LINE);
        return Uni.createFrom().item(radioStation);
    }

    public Uni<BroadcastingStats> checkStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        RadioStation radioStation = pool.get(name);
        if (radioStation != null) {
            stats.setStatus(radioStation.getStatus());
        } else {
            stats.setStatus(RadioStationStatus.OFF_LINE);
        }
        return Uni.createFrom().item(stats);
    }
}