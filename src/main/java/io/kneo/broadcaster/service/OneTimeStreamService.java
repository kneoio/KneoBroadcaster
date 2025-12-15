package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.dto.stream.OneTimeStreamDTO;
import io.kneo.broadcaster.dto.stream.StreamScheduleDTO;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.SceneScheduleEntry;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.model.stream.StreamSchedule;
import io.kneo.broadcaster.repository.BrandRepository;
import io.kneo.broadcaster.repository.OneTimeStreamRepository;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.ScheduleSongSupplier;
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
                    } else {
                        List<OneTimeStreamDTO> dtos = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.createFrom().item(dtos);
                    }
                });
    }

    public Uni<Integer> getAllCount() {
        return oneTimeStreamRepository.getAllCount();
    }

    public Uni<OneTimeStream> getById(UUID id) {
        return oneTimeStreamRepository.findById(id);
    }

    public OneTimeStreamDTO getDTO(OneTimeStream entity) {
        return mapToDTO(entity);
    }

    private OneTimeStreamDTO mapToDTO(OneTimeStream entity) {
        OneTimeStreamDTO dto = new OneTimeStreamDTO();
        dto.setId(entity.getId());
        dto.setSlugName(entity.getSlugName());
        dto.setLocalizedName(entity.getLocalizedName());
        dto.setTimeZone(entity.getTimeZone() != null ? entity.getTimeZone().getId() : null);
        dto.setBitRate(entity.getBitRate());
        dto.setBaseBrandId(entity.getBaseBrandId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setExpiresAt(entity.getExpiresAt());
        return dto;
    }

    public Uni<Void> run(OneTimeStreamRunReqDTO dto, IUser user) {
        return brandRepository.findById(dto.getBrandId(), user, true)
                .chain(sourceBrand -> scriptRepository.findById(dto.getScriptId(), user, false)
                        .chain(script -> Uni.createFrom().item(
                                prepaerOneTimeStream(sourceBrand, script, dto)
                        ))
                        .chain(s -> {
                            LOGGER.info("OneTimeStream: Initializing stream slugName={}", s);
                            return initializeOneTimeStream(s).replaceWithVoid();
                        })
                );
    }

    private OneTimeStream prepaerOneTimeStream(Brand sourceBrand, Script script, OneTimeStreamRunReqDTO dto) {
        OneTimeStream doc = new OneTimeStream(sourceBrand, script, dto.getUserVariables());

        doc.setScripts(List.of(new BrandScriptEntry(dto.getScriptId(), dto.getUserVariables())));
        doc.setSourceBrand(sourceBrand);
        doc.setSchedule(dto.getSchedule());
        //oneTimeStreamRepository.insert(doc);
        return doc;
    }

    public Uni<OneTimeStream> getBySlugName(String slugName) {
        return oneTimeStreamRepository.getBySlugName(slugName);
    }


    private Uni<IStream> initializeOneTimeStream(OneTimeStream oneTimeStream) {
        String streamSlugName = oneTimeStream.getSlugName();
        return radioStationPool.initializeStream(oneTimeStream)
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
                });
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
