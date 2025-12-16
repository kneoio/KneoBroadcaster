package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.dto.stream.OneTimeStreamDTO;
import io.kneo.broadcaster.dto.stream.StreamScheduleDTO;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.SceneScheduleEntry;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.model.stream.StreamSchedule;
import io.kneo.broadcaster.repository.BrandRepository;
import io.kneo.broadcaster.repository.OneTimeStreamRepository;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.ScheduleSongSupplier;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class OneTimeStreamService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OneTimeStreamService.class);

    @Inject
    BrandRepository brandRepository;

    @Inject
    ScriptRepository scriptRepository;

    @Inject
    OneTimeStreamRepository oneTimeStreamRepository;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    ScheduleSongSupplier scheduleSongSupplier;

    @Inject
    SceneService sceneService;

    public Uni<List<OneTimeStreamDTO>> getAll(int limit, int offset) {
        return oneTimeStreamRepository.getAll(limit, offset)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    return Uni.combine().all().unis(
                            list.stream()
                                    .map(this::mapToDTO)
                                    .toList()
                    ).with(items ->
                            items.stream()
                                    .map(OneTimeStreamDTO.class::cast)
                                    .toList()
                    );
                });
    }

    public Uni<Integer> getAllCount() {
        return oneTimeStreamRepository.getAllCount();
    }

    public Uni<OneTimeStream> getById(UUID id) {
        return oneTimeStreamRepository.findById(id);
    }

    // fix return type
    public Uni<OneTimeStreamDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return oneTimeStreamRepository.findById(id)
                .chain(this::mapToDTO);
    }

    private Uni<OneTimeStreamDTO> mapToDTO(OneTimeStream entity) {
        OneTimeStreamDTO dto = new OneTimeStreamDTO();
        dto.setId(entity.getId());
        dto.setSlugName(entity.getSlugName());
        dto.setLocalizedName(entity.getLocalizedName());
        dto.setTimeZone(entity.getTimeZone() != null ? entity.getTimeZone().getId() : null);
        dto.setBitRate(entity.getBitRate());
        dto.setBaseBrandId(entity.getBaseBrandId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setExpiresAt(entity.getExpiresAt());
        return Uni.createFrom().item(dto);
    }

    public Uni<OneTimeStream> run(OneTimeStreamRunReqDTO dto, IUser user) {
        return brandRepository.findById(dto.getBrandId(), user, true)
                .chain(sourceBrand -> scriptRepository.findById(dto.getScriptId(), user, false)
                        .chain(script -> {
                            OneTimeStream stream = new OneTimeStream(sourceBrand, script, dto.getUserVariables());
                            stream.setId(UUID.randomUUID());
                            stream.setScripts(List.of(new BrandScriptEntry(dto.getScriptId(), dto.getUserVariables())));
                            stream.setSourceBrand(sourceBrand);
                            stream.setSchedule(dto.getSchedule());
                            oneTimeStreamRepository.insert(stream);
                            return Uni.createFrom().item(stream);
                        })
                        .chain(stream -> {
                            LOGGER.info("OneTimeStream: Initializing stream slugName={}", stream.getSlugName());
                            String streamSlugName = stream.getSlugName();
                            return radioStationPool.initializeStream(stream)
                                    .onFailure().invoke(failure -> {
                                        LOGGER.error("Failed to initialize stream: {}", streamSlugName, failure);
                                        radioStationPool.get(streamSlugName)
                                                .subscribe().with(
                                                        station -> {
                                                            if (station != null) {
                                                                station.setStatus(RadioStationStatus.SYSTEM_ERROR);
                                                                LOGGER.warn("Stream {} status set to SYSTEM_ERROR due to initialization failure", streamSlugName);
                                                            }
                                                        },
                                                        error -> LOGGER.error("Failed to get station {} to set error status: {}", streamSlugName, error.getMessage(), error)
                                                );
                                    })
                                    .invoke(liveStream -> {
                                        if (liveStream != null) {
                                            stream.setStatus(liveStream.getStatus());
                                        }
                                    })
                                    .replaceWith(stream);
                        })
                );
    }


    public Uni<StreamScheduleDTO> buildStreamSchedule(UUID brandId, UUID scriptId, IUser user) {
        return brandRepository.findById(brandId, user, true)
                .chain(sourceBrand -> scriptRepository.findById(scriptId, user, false)
                        .chain(script -> sceneService.getAllWithPromptIds(scriptId, 100, 0, user)
                                .chain(scenes -> {
                                    script.setScenes(scenes);
                                    StreamSchedule schedule = new StreamSchedule();
                                    return schedule.build(script, sourceBrand, scheduleSongSupplier)
                                            .map(s -> {
                                                LOGGER.info("Built schedule for script '{}': {} scenes, {} total songs, ends at {}",
                                                        script.getName(),
                                                        s.getTotalScenes(),
                                                        s.getTotalSongs(),
                                                        s.getEstimatedEndTime());
                                                return toScheduleDTO(s);
                                            });
                                })));
    }

    public Uni<OneTimeStream> getBySlugName(String slugName) {
        return oneTimeStreamRepository.getBySlugName(slugName);
    }

    private StreamScheduleDTO toScheduleDTO(StreamSchedule schedule) {
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

        dto.setSourcing(scene.getSourcing().name());
        dto.setPlaylistTitle(scene.getPlaylistTitle());
        dto.setArtist(scene.getArtist());
        dto.setGenres(scene.getGenres());
        dto.setLabels(scene.getLabels());
        dto.setPlaylistItemTypes(scene.getPlaylistItemTypes().stream().map(Enum::name).collect(Collectors.toList()));
        dto.setSourceTypes(scene.getSourceTypes().stream().map(Enum::name).collect(Collectors.toList()));
        dto.setSearchTerm(scene.getSearchTerm());
        dto.setSoundFragments(scene.getSoundFragments());

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
        dto.setPlayed(song.isPlayed());
        return dto;
    }
}
