package io.kneo.broadcaster.service.dashboard;

import io.kneo.broadcaster.dto.dashboard.CountryStatsDTO;
import io.kneo.broadcaster.dto.dashboard.StationStatsDTO;
import io.kneo.broadcaster.model.cnst.SceneStatus;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.StreamSchedule;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.stats.StatsAccumulator;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.StreamManagerStats;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class StationDashboardService {

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    AiHelperService aiHelperService;

    @Inject
    StatsAccumulator statsAccumulator;

    public Uni<Optional<StationStatsDTO>> getStationStats(String brand) {
        return Uni.createFrom().item(() -> radioStationPool.getStation(brand))
                .flatMap(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().item(Optional.empty());
                    }
                    StationStatsDTO stats = createStationStats(brand, stream);
                    
                    // Get country stats from in-memory accumulator
                    List<CountryStatsDTO> countryStats = statsAccumulator.getCountryStats(brand).entrySet().stream()
                            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                            .limit(10)
                            .map(entry -> new CountryStatsDTO(entry.getKey(), entry.getValue()))
                            .collect(Collectors.toList());
                    stats.setListenersByCountry(countryStats);
                    
                    return aiHelperService.getAiDjStats(stream)
                            .onFailure().recoverWithItem(() -> null)
                            .map(aiDjStats -> {
                                stats.setAiDjStats(aiDjStats);
                                return Optional.of(stats);
                            });
                });
    }

    private StationStatsDTO createStationStats(String brand, IStream station) {
        StationStatsDTO stationStats = new StationStatsDTO();
        stationStats.setBrandName(brand);
        stationStats.setStatus(station.getStatus());
        stationStats.setStatusHistory(station.getStatusHistory());
        stationStats.setManagedBy(station.getManagedBy());
        stationStats.setCurrentListeners(statsAccumulator.getCurrentListeners(brand));
        ZoneId zone = station.getTimeZone();
        stationStats.setZoneId(zone.getId());

        if (station.getStreamManager() != null) {
            IStreamManager streamManager = station.getStreamManager();
            StreamManagerStats hlsSegmentStats = streamManager.getStats();
            stationStats.setHeartbeat(hlsSegmentStats.heartbeat());
            stationStats.setSongStatistics(hlsSegmentStats.getSongStatistics());
            PlaylistManager playlistManager = streamManager.getPlaylistManager();
            stationStats.setPlaylistManagerStats(playlistManager.getStats());
        }

        stationStats.setSchedule(buildScheduleEntries(station));

        return stationStats;
    }

    private List<StationStatsDTO.ScheduleEntryDTO> buildScheduleEntries(IStream station) {
        StreamSchedule schedule = station.getStreamSchedule();
        if (schedule == null || schedule.getLiveScenes().isEmpty()) {
            return List.of();
        }

        LocalTime now = LocalTime.now(station.getTimeZone());
        LiveScene activeEntry = null;
        if (station instanceof OneTimeStream) {
            activeEntry = station.findActiveScene();
        }

        List<StationStatsDTO.ScheduleEntryDTO> entries = new ArrayList<>();
        List<LiveScene> scenes = schedule.getLiveScenes();

        for (int i = 0; i < scenes.size(); i++) {
            LiveScene scene = scenes.get(i);
            LiveScene nextScene = (i < scenes.size() - 1) ? scenes.get(i + 1) : null;

            StationStatsDTO.ScheduleEntryDTO dto = new StationStatsDTO.ScheduleEntryDTO();
            dto.setSceneTitle(scene.getSceneTitle());
            dto.setStartTime(scene.getOriginalStartTime());
            dto.setEndTime(scene.getOriginalEndTime());

            if (activeEntry != null) {
                dto.setActive(activeEntry.getSceneId().equals(scene.getSceneId()));
            } else {
                dto.setActive(scene.isActiveAt(
                        now,
                        nextScene != null ? nextScene.getOriginalStartTime() : null
                ));
            }

            dto.setSourcing(scene.getSourcing() != null ? scene.getSourcing().name() : null);
            dto.setPlaylistTitle(scene.getPlaylistTitle());
            dto.setArtist(scene.getArtist());
            dto.setSearchTerm(scene.getSearchTerm());
            dto.setSongsCount(scene.getSongs() != null ? scene.getSongs().size() : 0);

            if (station instanceof OneTimeStream oneTimeStream) {
                dto.setFetchedSongsCount(
                        oneTimeStream.getFetchedSongsInScene(scene.getSceneId()).size()
                );
            } else {
                dto.setFetchedSongsCount(0);
            }

            dto.setActualStartTime(scene.getActualStartTime());
            dto.setActualEndTime(scene.getActualEndTime());

            LocalDateTime nowDateTime = LocalDateTime.now();
            SceneStatus status = computeSceneStatus(scene, nowDateTime);
            dto.setStatus(status);

            Long timingOffset = computeTimingOffset(scene, nowDateTime);
            dto.setTimingOffsetSeconds(timingOffset);

            entries.add(dto);
        }

        return entries;
    }


    private SceneStatus computeSceneStatus(LiveScene scene, LocalDateTime now) {
        if (scene.getActualEndTime() != null) {
            return SceneStatus.COMPLETED;
        }
        
        if (scene.getScheduledStartTime() != null && now.isAfter(scene.getScheduledEndTime())) {
            if (scene.getActualStartTime() == null) {
                return SceneStatus.SKIPPED;
            }
            return SceneStatus.COMPLETED;
        }
        
        if (scene.getActualStartTime() != null) {
            return SceneStatus.ACTIVE;
        }
        
        if (scene.getScheduledStartTime() != null && !now.isBefore(scene.getScheduledStartTime())) {
            return SceneStatus.ACTIVE;
        }
        
        return SceneStatus.PENDING;
    }

    private Long computeTimingOffset(LiveScene scene, LocalDateTime now) {
        if (scene.getActualStartTime() == null) {
            return null;
        }

        if (scene.getScheduledStartTime() == null) {
            return null;
        }

        LocalDateTime scheduledStart = scene.getScheduledStartTime();
        long scheduledElapsedSeconds = Duration.between(scheduledStart, now).getSeconds();
        long actualElapsedSeconds = Duration.between(scene.getActualStartTime(), now).getSeconds();

        return actualElapsedSeconds - scheduledElapsedSeconds;
    }
}