package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.controller.stream.PlaylistRange;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.stream.HlsTimerService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RadioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioService.class);

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    HlsTimerService timerService;

    @Inject
    SoundFragmentService soundFragmentService;

    @Inject
    HlsPlaylistConfig hlsConfig;

    private final Map<String, Boolean> activeStations = new ConcurrentHashMap<>();

    public Uni<RadioStation> initializeStation(String brand) {
        LOGGER.info("Initializing station for brand: {}", brand);
        activeStations.put(brand, true);
        return radioStationPool.initializeStation(brand)
                .onItem().invoke(station -> ensureContinuousPlayback(brand))
                .onFailure().invoke(failure -> LOGGER.error("Failed to initialize station for brand: {}", brand, failure));
    }

    public Uni<RadioStation> stopStation(String brand) {
        LOGGER.info("Stop brand: {}", brand);
        activeStations.put(brand, false);
        return radioStationPool.stop(brand)
                .onFailure().invoke(failure -> LOGGER.error("Failed to stop station for brand: {}", brand, failure));
    }

    public Uni<HLSPlaylist> getPlaylist(String brand) {
        return radioStationPool.get(brand)
                .onItem().transform(station -> {
                    if (station == null || station.getPlaylist() == null || station.getPlaylist().getSegmentCount() == 0) {
                        LOGGER.warn("Station not initialized for brand: {}", brand);
                        throw new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE);
                    }
                    return (HLSPlaylist) station.getPlaylist();
                })
                .onFailure().invoke(failure -> LOGGER.error("Failed to get playlist for brand: {}", brand, failure));
    }

    private void ensureContinuousPlayback(String brand) {

            LOGGER.info("Starting continuous playback for brand: {}", brand);
            timerService.getTicker().subscribe().with(
                    timestamp -> {
                        if (!activeStations.getOrDefault(brand, false)) return;

                        try {
                            radioStationPool.get(brand).subscribe().with(
                                    station -> {
                                        if (station != null && station.getPlaylist() != null) {
                                            generateSegmentFromNextTrack(brand, timestamp, (HLSPlaylist)station.getPlaylist());
                                        }
                                    },
                                    error -> LOGGER.error("Error in segment generation for {}: {}", brand, error.getMessage())
                            );
                        } catch (Exception e) {
                            LOGGER.error("Unexpected error in continuous playback for {}: {}", brand, e.getMessage());
                        }
                    },
                    error -> LOGGER.error("Timer subscription error for brand {}: {}", brand, error.getMessage())
            );

    }

    private void generateSegmentFromNextTrack(String brand, long timestamp, HLSPlaylist playlist) {
        soundFragmentService.getForBrand(brand, 1, true).subscribe().with(
                fragments -> {
                    if (fragments == null || fragments.isEmpty()) return;

                    try {
                        BrandSoundFragment fragment = fragments.get(0);
                        SoundFragment soundFragment = fragment.getSoundFragment();

                        Path filePath = soundFragment.getFilePath();
                        String songName = soundFragment.getTitle();
                        UUID fragmentId = soundFragment.getId();

                        try {
                            byte[] data = Files.readAllBytes(filePath);

                            long sequenceNumber = playlist.getCurrentSequenceAndIncrement();

                            HlsSegment segment = new HlsSegment(
                                    sequenceNumber,
                                    data,
                                    hlsConfig.getSegmentDuration(),
                                    fragmentId,
                                    songName,
                                    timestamp
                            );

                            playlist.addSegment(segment);

                            long start = Math.max(0, sequenceNumber - 10);
                            playlist.addRangeToQueue(new PlaylistRange(start, sequenceNumber));
                        } catch (Exception e) {
                            LOGGER.error("Error creating segment: {}", e.getMessage());
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error processing segment: {}", e.getMessage());
                    }
                },
                error -> LOGGER.error("Error fetching fragments for {}: {}", brand, error.getMessage())
        );
    }

    public boolean isStationActive(String brand) {
        return activeStations.getOrDefault(brand, false);
    }
}