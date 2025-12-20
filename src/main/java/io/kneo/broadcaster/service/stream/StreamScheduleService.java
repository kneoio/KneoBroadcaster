package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.dto.stream.StreamScheduleDTO;
import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.SceneScheduleEntry;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.model.stream.StreamSchedule;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.SceneService;
import io.kneo.broadcaster.service.ScriptService;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class StreamScheduleService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamScheduleService.class);

    @Inject
    BrandService brandService;

    @Inject
    ScriptService scriptService;

    @Inject
    ScheduleSongSupplier scheduleSongSupplier;

    @Inject
    SceneService sceneService;

    public Uni<StreamSchedule> buildStreamSchedule(UUID brandId, UUID scriptId, IUser user) {
        return brandService.getById(brandId, user)
                .chain(sourceBrand ->
                        scriptService.getById(scriptId, user)
                                .chain(script ->
                                        sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
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

    public Uni<StreamSchedule> buildLoopedStreamSchedule(UUID brandId, UUID scriptId, IUser user) {
        return brandService.getById(brandId, user)
                .chain(sourceBrand ->
                        scriptService.getById(scriptId, user)
                                .chain(script ->
                                        sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                                                .invoke(script::setScenes)
                                                .chain(x -> buildLoopedSchedule(script, sourceBrand, scheduleSongSupplier))
                                )
                );
    }


    public Uni<StreamSchedule> build(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        StreamSchedule schedule = new StreamSchedule(LocalDateTime.now());

        List<Scene> scenes = script.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(schedule);
        }

        List<Uni<SceneScheduleEntry>> sceneUnis = new ArrayList<>();
        LocalDateTime sceneStartTime = LocalDateTime.now();

        for (Scene scene : scenes) {
            LocalDateTime finalSceneStartTime = sceneStartTime;
            sceneUnis.add(
                    fetchSongsForScene(sourceBrand, scene, songSupplier)
                            .map(songs -> {
                                SceneScheduleEntry entry = new SceneScheduleEntry(scene, finalSceneStartTime);
                                LocalDateTime songStartTime = finalSceneStartTime;
                                for (SoundFragment song : songs) {
                                    ScheduledSongEntry songEntry = new ScheduledSongEntry(song, songStartTime);
                                    entry.addSong(songEntry);
                                    songStartTime = songStartTime.plusSeconds(songEntry.getEstimatedDurationSeconds());
                                }
                                return entry;
                            })
            );
            sceneStartTime = sceneStartTime.plusSeconds(scene.getDurationSeconds());
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    entries.forEach(schedule::addSceneSchedule);
                    return schedule;
                });
    }

    private Uni<List<SoundFragment>> fetchSongsForScene(Brand brand, Scene scene, ScheduleSongSupplier songSupplier) {
        int quantity = estimateSongsForScene(scene);
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();

        if (playlistRequest == null) {
            return songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
        }

        WayOfSourcing sourcing = playlistRequest.getSourcing();
        if (sourcing == null) {
            return songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
        }

        return switch (sourcing) {
            case QUERY -> {
                PlaylistRequest req = new PlaylistRequest();
                req.setSearchTerm(playlistRequest.getSearchTerm());
                req.setGenres(playlistRequest.getGenres());
                req.setLabels(playlistRequest.getLabels());
                req.setType(playlistRequest.getType());
                req.setSource(playlistRequest.getSource());
                yield songSupplier.getSongsByQuery(brand.getId(), req, quantity);
            }
            case STATIC_LIST -> songSupplier.getSongsFromStaticList(playlistRequest.getSoundFragments(), quantity);
            default -> songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
        };
    }

    private StreamScheduleDTO toScheduleDTO(StreamSchedule schedule) {
        if (schedule == null) {
            return null;
        }
        StreamScheduleDTO dto = new StreamScheduleDTO();
        dto.setCreatedAt(schedule.getCreatedAt());
        dto.setEstimatedEndTime(schedule.getEstimatedEndTime());
        dto.setTotalScenes(schedule.getTotalScenes());
        dto.setTotalSongs(schedule.getTotalSongs());

        List<StreamScheduleDTO.SceneScheduleDTO> sceneDTOs = schedule.getSceneSchedules().stream()
                .map(this::toSceneDTO)
                .collect(Collectors.toList());
        dto.setScenes(sceneDTOs);

        return dto;
    }

    private StreamScheduleDTO.SceneScheduleDTO toSceneDTO(SceneScheduleEntry scene) {
        StreamScheduleDTO.SceneScheduleDTO dto = new StreamScheduleDTO.SceneScheduleDTO();
        dto.setSceneId(scene.getSceneId().toString());
        dto.setSceneTitle(scene.getSceneTitle());
        dto.setScheduledStartTime(scene.getScheduledStartTime());
        dto.setScheduledEndTime(scene.getScheduledEndTime());
        dto.setDurationSeconds(scene.getDurationSeconds());

        dto.setOriginalStartTime(scene.getOriginalStartTime());
        dto.setOriginalEndTime(scene.getOriginalEndTime());
        dto.setSourcing(scene.getSourcing() != null ? scene.getSourcing().name() : null);
        dto.setPlaylistTitle(scene.getPlaylistTitle());
        dto.setArtist(scene.getArtist());
        dto.setGenres(scene.getGenres() != null ? scene.getGenres() : List.of());
        dto.setLabels(scene.getLabels() != null ? scene.getLabels() : List.of());
        dto.setPlaylistItemTypes(scene.getPlaylistItemTypes() != null
                ? scene.getPlaylistItemTypes().stream().map(Enum::name).collect(Collectors.toList())
                : List.of());
        dto.setSourceTypes(scene.getSourceTypes() != null
                ? scene.getSourceTypes().stream().map(Enum::name).collect(Collectors.toList())
                : List.of());
        dto.setSearchTerm(scene.getSearchTerm() != null ? scene.getSearchTerm() : "");
        dto.setSoundFragments(scene.getSoundFragments() != null ? scene.getSoundFragments() : List.of());

        List<StreamScheduleDTO.ScheduledSongDTO> songDTOs = scene.getSongs().stream()
                .map(this::toSongDTO)
                .collect(Collectors.toList());
        dto.setSongs(songDTOs);

        return dto;
    }

    private StreamScheduleDTO.ScheduledSongDTO toSongDTO(ScheduledSongEntry song) {
        StreamScheduleDTO.ScheduledSongDTO dto = new StreamScheduleDTO.ScheduledSongDTO();
        dto.setId(song.getId().toString());
        dto.setSongId(song.getSoundFragment().getId().toString());
        dto.setTitle(song.getSoundFragment().getTitle());
        dto.setArtist(song.getSoundFragment().getArtist());
        dto.setScheduledStartTime(song.getScheduledStartTime());
        dto.setEstimatedDurationSeconds(song.getEstimatedDurationSeconds());
        return dto;
    }

    private StreamSchedule fromScheduleDTO(StreamScheduleDTO dto) {
        if (dto == null) {
            return null;
        }
        StreamSchedule schedule = new StreamSchedule(dto.getCreatedAt());
        if (dto.getScenes() != null) {
            for (StreamScheduleDTO.SceneScheduleDTO sceneDTO : dto.getScenes()) {
                SceneScheduleEntry sceneEntry = fromSceneDTO(sceneDTO);
                schedule.addSceneSchedule(sceneEntry);
            }
        }
        return schedule;
    }

    private SceneScheduleEntry fromSceneDTO(StreamScheduleDTO.SceneScheduleDTO dto) {
        SceneScheduleEntry entry = new SceneScheduleEntry(
                UUID.fromString(dto.getSceneId()),
                dto.getSceneTitle(),
                dto.getScheduledStartTime(),
                dto.getDurationSeconds(),
                dto.getOriginalStartTime(),
                dto.getOriginalEndTime(),
                dto.getSourcing() != null ? WayOfSourcing.valueOf(dto.getSourcing()) : null,
                dto.getPlaylistTitle(),
                dto.getArtist(),
                dto.getGenres(),
                dto.getLabels(),
                dto.getPlaylistItemTypes() != null ? dto.getPlaylistItemTypes().stream().map(PlaylistItemType::valueOf).toList() : null,
                dto.getSourceTypes() != null ? dto.getSourceTypes().stream().map(SourceType::valueOf).toList() : null,
                dto.getSearchTerm(),
                dto.getSoundFragments()
        );
        if (dto.getSongs() != null) {
            for (StreamScheduleDTO.ScheduledSongDTO songDTO : dto.getSongs()) {
                entry.addSong(fromSongDTO(songDTO));
            }
        }
        return entry;
    }

    private ScheduledSongEntry fromSongDTO(StreamScheduleDTO.ScheduledSongDTO dto) {
        SoundFragment soundFragment = new SoundFragment();
        soundFragment.setId(UUID.fromString(dto.getSongId()));
        soundFragment.setTitle(dto.getTitle());
        soundFragment.setArtist(dto.getArtist());
        return new ScheduledSongEntry(
                UUID.fromString(dto.getId()),
                soundFragment,
                dto.getScheduledStartTime()
        );
    }

    private int estimateSongsForScene(Scene scene) {
        int durationSeconds = scene.getDurationSeconds();
        int avgSongDuration = 180;
        double djTalkRatio = scene.getTalkativity();
        int effectiveMusicTime = (int) (durationSeconds * (1 - djTalkRatio * 0.3));
        return Math.max(1, effectiveMusicTime / avgSongDuration);
    }

    public Uni<StreamSchedule> buildLoopedSchedule(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        StreamSchedule schedule = new StreamSchedule(LocalDateTime.now());

        List<Scene> scenes = script.getScenes();
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

        List<Uni<SceneScheduleEntry>> sceneUnis = new ArrayList<>();
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
                                SceneScheduleEntry entry = new SceneScheduleEntry(
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
                                        scene.getPlaylistRequest() != null ? scene.getPlaylistRequest().getSoundFragments() : null
                                );
                                LocalDateTime songStartTime = finalSceneStartTime;
                                for (SoundFragment song : songs) {
                                    ScheduledSongEntry songEntry = new ScheduledSongEntry(song, songStartTime);
                                    entry.addSong(songEntry);
                                    songStartTime = songStartTime.plusSeconds(songEntry.getEstimatedDurationSeconds());
                                }
                                return entry;
                            })
            );
            sceneStartTime = sceneStartTime.plusSeconds(durationSeconds);
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    entries.forEach(schedule::addSceneSchedule);
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
        int quantity = estimateSongsForDuration(durationSeconds, scene.getTalkativity());
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();

        if (playlistRequest == null) {
            return songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
        }

        WayOfSourcing sourcing = playlistRequest.getSourcing();
        if (sourcing == null) {
            return songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
        }

        return switch (sourcing) {
            case QUERY -> {
                PlaylistRequest req = new PlaylistRequest();
                req.setSearchTerm(playlistRequest.getSearchTerm());
                req.setGenres(playlistRequest.getGenres());
                req.setLabels(playlistRequest.getLabels());
                req.setType(playlistRequest.getType());
                req.setSource(playlistRequest.getSource());
                yield songSupplier.getSongsByQuery(brand.getId(), req, quantity);
            }
            case STATIC_LIST -> songSupplier.getSongsFromStaticList(playlistRequest.getSoundFragments(), quantity);
            default -> songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
        };
    }

    private int estimateSongsForDuration(int durationSeconds, double talkativity) {
        int avgSongDuration = 180;
        int effectiveMusicTime = (int) (durationSeconds * (1 - talkativity * 0.3));
        return Math.max(1, effectiveMusicTime / avgSongDuration);
    }
}
