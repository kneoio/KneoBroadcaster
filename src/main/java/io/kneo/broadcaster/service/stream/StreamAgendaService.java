package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.dto.stream.StreamScheduleDTO;
import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.model.stream.StreamAgenda;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.SceneService;
import io.kneo.broadcaster.service.ScriptService;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class StreamAgendaService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamAgendaService.class);
    private static final int AVG_DJ_INTRO_SECONDS = 30;

    @Inject
    BrandService brandService;

    @Inject
    ScriptService scriptService;

    @Inject
    ScheduleSongSupplier scheduleSongSupplier;

    @Inject
    SceneService sceneService;

    public Uni<StreamAgenda> buildStreamSchedule(UUID brandId, UUID scriptId, IUser user) {
        return brandService.getById(brandId, user)
                .chain(sourceBrand ->
                        scriptService.getById(scriptId, user)
                                .chain(script ->
                                        sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                                                .map(list -> new TreeSet<>(
                                                        Comparator.comparingInt(Scene::getSeqNum)
                                                                .thenComparing(Scene::getId)
                                                ) {{
                                                    addAll(list);
                                                }})
                                                .invoke(script::setScenes)
                                                .chain(x -> build(script, sourceBrand, scheduleSongSupplier))
                                )
                );
    }


    public Uni<StreamScheduleDTO> getStreamScheduleDTO(UUID brandId, UUID scriptId, IUser user) {
        return buildStreamSchedule(brandId, scriptId, user)
                .map(s -> {
                    LOGGER.info(
                            "Built schedule for script: {} scenes, {} total songs, ends at {}",
                            s.getTotalScenes(),
                            s.getTotalSongs(),
                            s.getEstimatedEndTime()
                    );
                    return toScheduleDTO(s);
                });
    }

    public Uni<StreamAgenda> buildLoopedStreamSchedule(UUID brandId, UUID scriptId, IUser user) {
        return brandService.getById(brandId, user)
                .chain(sourceBrand ->
                        scriptService.getById(scriptId, user)
                                .chain(script ->
                                        sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                                                .map(list -> new TreeSet<>(
                                                        Comparator.comparingInt(Scene::getSeqNum)
                                                                .thenComparing(Scene::getId)
                                                ) {{
                                                    addAll(list);
                                                }})
                                                .invoke(script::setScenes)
                                                .chain(x -> buildLoopedSchedule(script, sourceBrand, scheduleSongSupplier))
                                )
                );
    }


    public Uni<StreamAgenda> build(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        StreamAgenda schedule = new StreamAgenda(LocalDateTime.now());

        NavigableSet<Scene> scenes = script.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        List<Uni<LiveScene>> sceneUnis = new ArrayList<>();
        LocalDateTime sceneStartTime = LocalDateTime.now();

        for (Scene scene : scenes) {
            LocalDateTime finalSceneStartTime = sceneStartTime;
            sceneUnis.add(
                    fetchSongsForScene(sourceBrand, scene, songSupplier)
                            .map(songs -> {
                                LiveScene entry = new LiveScene(scene, finalSceneStartTime);
                                LocalDateTime songStartTime = finalSceneStartTime;
                                for (SoundFragment song : songs) {
                                    ScheduledSongEntry songEntry = new ScheduledSongEntry(song, songStartTime);
                                    entry.addSong(songEntry);
                                    songStartTime = songStartTime.plusSeconds(songEntry.getDurationSeconds());
                                }
                                return entry;
                            })
            );
            sceneStartTime = sceneStartTime.plusSeconds(scene.getDurationSeconds());
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    entries.forEach(schedule::addScene);
                    return schedule;
                });
    }

    private Uni<List<SoundFragment>> fetchSongsForScene(Brand brand, Scene scene, ScheduleSongSupplier songSupplier) {
        int sceneDurationSeconds = scene.getDurationSeconds();
        int maxSongsNeeded = (int) Math.ceil(sceneDurationSeconds / 120.0 * 1.5) + 2;
        
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();

        Uni<List<SoundFragment>> songsPoolUni;
        if (playlistRequest == null) {
            songsPoolUni = songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
        } else {
            WayOfSourcing sourcing = playlistRequest.getSourcing();
            if (sourcing == null) {
                songsPoolUni = songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
            } else {
                songsPoolUni = switch (sourcing) {
                    case QUERY -> {
                        PlaylistRequest req = new PlaylistRequest();
                        req.setSearchTerm(playlistRequest.getSearchTerm());
                        req.setGenres(playlistRequest.getGenres());
                        req.setLabels(playlistRequest.getLabels());
                        req.setType(playlistRequest.getType());
                        req.setSource(playlistRequest.getSource());
                        yield songSupplier.getSongsByQuery(brand.getId(), req, maxSongsNeeded);
                    }
                    case STATIC_LIST -> songSupplier.getSongsFromStaticList(playlistRequest.getSoundFragments(), maxSongsNeeded);
                    default -> songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
                };
            }
        }

        return songsPoolUni.map(songsPool -> selectSongsToFitDuration(songsPool, sceneDurationSeconds));
    }

    private List<SoundFragment> selectSongsToFitDuration(List<SoundFragment> songsPool, int sceneDurationSeconds) {
        if (songsPool.isEmpty()) {
            return songsPool;
        }

        List<SoundFragment> selectedSongs = new ArrayList<>();
        int totalTimeUsed = 0;

        for (SoundFragment song : songsPool) {
            int songDurationSeconds = song.getLength() != null ? (int) song.getLength().toSeconds() : 180;
            int timeWithIntro = songDurationSeconds + AVG_DJ_INTRO_SECONDS;

            if (totalTimeUsed + timeWithIntro <= sceneDurationSeconds) {
                selectedSongs.add(song);
                totalTimeUsed += timeWithIntro;
            } else if (selectedSongs.isEmpty()) {
                selectedSongs.add(song);
                totalTimeUsed += timeWithIntro;
                break;
            } else {
                int gap = sceneDurationSeconds - totalTimeUsed;
                if (gap > 60) {
                    selectedSongs.add(song);
                    totalTimeUsed += timeWithIntro;
                }
            }
        }

        LOGGER.debug("Scene duration: {}s, Pool size: {}, Selected {} songs with total time: {}s, Gap: {}s", 
                sceneDurationSeconds, songsPool.size(), selectedSongs.size(), totalTimeUsed, 
                sceneDurationSeconds - totalTimeUsed);

        return selectedSongs;
    }

    public StreamScheduleDTO toScheduleDTO(StreamAgenda schedule) {
        if (schedule == null) {
            return null;
        }
        StreamScheduleDTO dto = new StreamScheduleDTO();
        dto.setCreatedAt(schedule.getCreatedAt());
        dto.setEstimatedEndTime(schedule.getEstimatedEndTime());
        dto.setTotalScenes(schedule.getTotalScenes());
        dto.setTotalSongs(schedule.getTotalSongs());

        List<StreamScheduleDTO.SceneScheduleDTO> sceneDTOs = schedule.getLiveScenes().stream()
                .map(this::toSceneDTO)
                .collect(Collectors.toList());
        dto.setScenes(sceneDTOs);

        return dto;
    }

    private StreamScheduleDTO.SceneScheduleDTO toSceneDTO(LiveScene scene) {
        StreamScheduleDTO.SceneScheduleDTO dto = new StreamScheduleDTO.SceneScheduleDTO();
        dto.setSceneId(scene.getSceneId().toString());
        dto.setSceneTitle(scene.getSceneTitle());
        dto.setScheduledStartTime(scene.getScheduledStartTime());
        dto.setScheduledEndTime(scene.getScheduledEndTime());
        dto.setDurationSeconds(scene.getDurationSeconds());

        dto.setOriginalStartTime(scene.getOriginalStartTime());
        dto.setOriginalEndTime(scene.getOriginalEndTime());
        dto.setPlaylistRequest(toScenePlaylistRequest(scene));

        List<StreamScheduleDTO.ScheduledSongDTO> songDTOs = scene.getSongs().stream()
                .map(this::toSongDTO)
                .collect(Collectors.toList());
        dto.setSongs(songDTOs);

        List<String> warnings = new ArrayList<>();
        
        int totalSongDuration = scene.getSongs().stream()
                .mapToInt(ScheduledSongEntry::getDurationSeconds)
                .sum();
        
        int sceneDuration = scene.getDurationSeconds();
        int avgIntroDuration = 30;
        int estimatedTotalWithIntros = totalSongDuration + (scene.getSongs().size() * avgIntroDuration);
        
        if (scene.getSongs().isEmpty()) {
            warnings.add("⚠️ No songs scheduled: Scene will be silent unless DJ fills the entire duration.");
        } else if (estimatedTotalWithIntros > sceneDuration) {
            int overflow = estimatedTotalWithIntros - sceneDuration;
            double overflowMinutes = overflow / 60.0;
            warnings.add(String.format("⚠️ Songs extend beyond scene: Total duration (~%.1f min) exceeds scene duration (%.1f min) by ~%.1f min. Scene will run longer than planned.",
                    estimatedTotalWithIntros / 60.0, sceneDuration / 60.0, overflowMinutes));
        }
        
        if (!warnings.isEmpty()) {
            dto.setWarning(String.join(" ", warnings));
        }

        return dto;
    }

    private StreamScheduleDTO.ScenePlaylistRequest toScenePlaylistRequest(LiveScene scene) {
        StreamScheduleDTO.ScenePlaylistRequest request = new StreamScheduleDTO.ScenePlaylistRequest();
        request.setSourcing(scene.getSourcing() != null ? scene.getSourcing().name() : null);
        request.setPlaylistTitle(scene.getPlaylistTitle());
        request.setArtist(scene.getArtist());
        request.setGenres(scene.getGenres() != null ? scene.getGenres() : List.of());
        request.setLabels(scene.getLabels() != null ? scene.getLabels() : List.of());
        request.setPlaylistItemTypes(scene.getPlaylistItemTypes() != null
                ? scene.getPlaylistItemTypes().stream().map(Enum::name).collect(Collectors.toList())
                : List.of());
        request.setSourceTypes(scene.getSourceTypes() != null
                ? scene.getSourceTypes().stream().map(Enum::name).collect(Collectors.toList())
                : List.of());
        request.setSearchTerm(scene.getSearchTerm() != null ? scene.getSearchTerm() : "");
        request.setSoundFragments(scene.getSoundFragments() != null ? scene.getSoundFragments() : List.of());
        return request;
    }

    private StreamScheduleDTO.ScheduledSongDTO toSongDTO(ScheduledSongEntry song) {
        StreamScheduleDTO.ScheduledSongDTO dto = new StreamScheduleDTO.ScheduledSongDTO();
        dto.setId(song.getId().toString());
        dto.setSongId(song.getSoundFragment().getId().toString());
        dto.setTitle(song.getSoundFragment().getTitle());
        dto.setArtist(song.getSoundFragment().getArtist());
        dto.setScheduledStartTime(song.getScheduledStartTime());
        dto.setEstimatedDurationSeconds(song.getDurationSeconds());
        return dto;
    }

    public Uni<StreamAgenda> buildLoopedSchedule(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        StreamAgenda schedule = new StreamAgenda(LocalDateTime.now());

        NavigableSet<Scene> scenes = script.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        List<Scene> sortedScenes = scenes.stream()
                .filter(s -> s.getStartTime() != null)
                .sorted(Comparator.comparing(Scene::getStartTime))
                .collect(Collectors.toList());

        if (sortedScenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalTime currentTime = now.toLocalTime();
        LocalDate today = now.toLocalDate();

        int activeIndex = findActiveSceneIndex(sortedScenes, currentTime);

        List<Uni<LiveScene>> sceneUnis = new ArrayList<>();
        LocalDateTime sceneStartTime = now;

        for (int i = 0; i < sortedScenes.size(); i++) {
            int sceneIndex = (activeIndex + i) % sortedScenes.size();
            Scene scene = sortedScenes.get(sceneIndex);
            int nextIndex = (sceneIndex + 1) % sortedScenes.size();
            Scene nextScene = sortedScenes.get(nextIndex);

            int durationSeconds = calculateDurationUntilNext(scene.getStartTime(), nextScene.getStartTime());

            if (i == 0) {
                int elapsedInCurrentScene = calculateElapsedSeconds(scene.getStartTime(), currentTime);
                durationSeconds = Math.max(0, durationSeconds - elapsedInCurrentScene);
            }

            LocalDateTime finalSceneStartTime = sceneStartTime;
            int finalDurationSeconds = durationSeconds;

            LocalTime sceneOriginalStart = scene.getStartTime();
            LocalTime sceneOriginalEnd = nextScene.getStartTime();

            sceneUnis.add(
                    fetchSongsForSceneWithDuration(sourceBrand, scene, finalDurationSeconds, songSupplier)
                            .map(songs -> {
                                LiveScene entry = new LiveScene(
                                        scene.getId(),
                                        scene.getTitle(),
                                        finalSceneStartTime,
                                        finalDurationSeconds,
                                        sceneOriginalStart,
                                        sceneOriginalEnd,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSourcing() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getTitle() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getArtist() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getGenres() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getLabels() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getType() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSource() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSearchTerm() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSoundFragments() : null,
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getPrompts() : null
                                );
                                LocalDateTime songStartTime = finalSceneStartTime;
                                for (SoundFragment song : songs) {
                                    ScheduledSongEntry songEntry = new ScheduledSongEntry(song, songStartTime);
                                    entry.addSong(songEntry);
                                    songStartTime = songStartTime.plusSeconds(songEntry.getDurationSeconds());
                                }
                                return entry;
                            })
            );
            sceneStartTime = sceneStartTime.plusSeconds(durationSeconds);
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    entries.forEach(schedule::addScene);
                    return schedule;
                });
    }

    private int findActiveSceneIndex(List<Scene> sortedScenes, LocalTime currentTime) {
        for (int i = sortedScenes.size() - 1; i >= 0; i--) {
            if (!currentTime.isBefore(sortedScenes.get(i).getStartTime())) {
                return i;
            }
        }
        return sortedScenes.size() - 1;
    }

    private int calculateDurationUntilNext(LocalTime start, LocalTime next) {
        int startSeconds = start.toSecondOfDay();
        int nextSeconds = next.toSecondOfDay();
        if (nextSeconds > startSeconds) {
            return nextSeconds - startSeconds;
        } else {
            return (24 * 60 * 60 - startSeconds) + nextSeconds;
        }
    }

    private int calculateElapsedSeconds(LocalTime sceneStart, LocalTime currentTime) {
        int sceneStartSeconds = sceneStart.toSecondOfDay();
        int currentSeconds = currentTime.toSecondOfDay();
        if (currentSeconds >= sceneStartSeconds) {
            return currentSeconds - sceneStartSeconds;
        } else {
            return (24 * 60 * 60 - sceneStartSeconds) + currentSeconds;
        }
    }

    private Uni<List<SoundFragment>> fetchSongsForSceneWithDuration(Brand brand, Scene scene, int durationSeconds, ScheduleSongSupplier songSupplier) {
        int maxSongsNeeded = (durationSeconds / 120) + 2;
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();

        Uni<List<SoundFragment>> songsPoolUni;
        if (playlistRequest == null) {
            songsPoolUni = songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
        } else {
            WayOfSourcing sourcing = playlistRequest.getSourcing();
            if (sourcing == null) {
                songsPoolUni = songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
            } else {
                songsPoolUni = switch (sourcing) {
                    case QUERY -> {
                        PlaylistRequest req = new PlaylistRequest();
                        req.setSearchTerm(playlistRequest.getSearchTerm());
                        req.setGenres(playlistRequest.getGenres());
                        req.setLabels(playlistRequest.getLabels());
                        req.setType(playlistRequest.getType());
                        req.setSource(playlistRequest.getSource());
                        yield songSupplier.getSongsByQuery(brand.getId(), req, maxSongsNeeded);
                    }
                    case STATIC_LIST -> songSupplier.getSongsFromStaticList(playlistRequest.getSoundFragments(), maxSongsNeeded);
                    default -> songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, maxSongsNeeded);
                };
            }
        }

        return songsPoolUni.map(songsPool -> selectSongsToFitDurationWithTalkativity(songsPool, durationSeconds, scene.getTalkativity()));
    }

    private List<SoundFragment> selectSongsToFitDurationWithTalkativity(List<SoundFragment> songsPool, int sceneDurationSeconds, double talkativity) {
        if (songsPool.isEmpty()) {
            return songsPool;
        }

        int effectiveMusicTime = (int) (sceneDurationSeconds * (1 - talkativity * 0.3));

        List<SoundFragment> selectedSongs = new ArrayList<>();
        int totalTimeUsed = 0;

        for (SoundFragment song : songsPool) {
            int songDurationSeconds = song.getLength() != null ? (int) song.getLength().toSeconds() : 180;
            int timeWithIntro = songDurationSeconds + AVG_DJ_INTRO_SECONDS;

            if (totalTimeUsed + timeWithIntro <= effectiveMusicTime) {
                selectedSongs.add(song);
                totalTimeUsed += timeWithIntro;
            } else if (selectedSongs.isEmpty()) {
                selectedSongs.add(song);
                break;
            } else {
                break;
            }
        }

        LOGGER.debug("RadioStream scene duration: {}s, effective music time: {}s (talkativity: {}), Selected {} songs with total time: {}s",
                sceneDurationSeconds, effectiveMusicTime, talkativity, selectedSongs.size(), totalTimeUsed);

        return selectedSongs;
    }

    private SoundFragment cloneSoundFragment(SoundFragment original) {
        SoundFragment clone = new SoundFragment();
        clone.setId(original.getId());
        clone.setSource(original.getSource());
        clone.setStatus(original.getStatus());
        clone.setType(original.getType());
        clone.setTitle(original.getTitle());
        clone.setArtist(original.getArtist());
        clone.setGenres(original.getGenres() != null ? new ArrayList<>(original.getGenres()) : null);
        clone.setLabels(original.getLabels() != null ? new ArrayList<>(original.getLabels()) : null);
        clone.setAlbum(original.getAlbum());
        clone.setSlugName(original.getSlugName());
        clone.setLength(original.getLength() != null ? Duration.ofMillis(original.getLength().toMillis()) : null);
        clone.setDescription(original.getDescription());
        clone.setArchived(original.getArchived());
        return clone;
    }
}
