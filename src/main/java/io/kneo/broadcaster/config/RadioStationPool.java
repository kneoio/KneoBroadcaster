package io.kneo.broadcaster.config;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BroadcastingStats;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.radio.PlaylistKeeper;
import io.kneo.broadcaster.service.radio.PlaylistMaintenanceService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

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
    private BroadcasterConfig broadcasterConfig;

    @Inject
    private SoundFragmentService soundFragmentService;

    @Inject
    private PlaylistKeeper playlistKeeper;

    @Inject
    private PlaylistMaintenanceService maintenanceService;

    public Uni<RadioStation> initializeStation(String brandName) {
        HLSPlaylist playlist = new HLSPlaylist(
                config,
                broadcasterConfig,
                soundFragmentService,
                brandName,
                playlistKeeper,
                maintenanceService
        );
        playlist.initialize();

        return radioStationService.findByBrandName(brandName)
                .onItem().invoke(station -> {
                    station.setPlaylist(playlist);
                    station.setStatus(RadioStationStatus.WARMING_UP);
                    pool.put(brandName, station);
                })
                .onItem().transformToUni(station -> {
                    return Uni.createFrom().emitter(emitter -> {
                        CompletableFuture.runAsync(() -> {
                            try {
                                while (playlist.getSegmentCount() < 100) {
                                    Thread.sleep(300);
                                }
                                station.setStatus(RadioStationStatus.ON_LINE);
                                emitter.complete(station);
                            } catch (Exception e) {
                                emitter.fail(e);
                            }
                        });
                    });
                });
    }

    public Uni<RadioStation> get(String brandName) {
        RadioStation radioStation = pool.get(brandName);
        return Uni.createFrom().item(radioStation);
    }

    public Uni<RadioStation> stop(String brandName) {
        RadioStation radioStation = pool.get(brandName);
        radioStation.getPlaylist().shutdown();
        radioStation.setStatus(RadioStationStatus.OFF_LINE);
        return Uni.createFrom().item(radioStation);
    }

    public Uni<BroadcastingStats> checkStatus(String name) {
        BroadcastingStats stats = new BroadcastingStats();
        RadioStation radioStation = pool.get(name);
        if (radioStation != null) {
            stats.setStatus(radioStation.getStatus());
            HLSPlaylist playlist = radioStation.getPlaylist();
            if (playlist != null) {
                stats.setSegmentsCount(playlist.getSegmentCount());
            }
        }
        return Uni.createFrom().item(stats);
    }
}