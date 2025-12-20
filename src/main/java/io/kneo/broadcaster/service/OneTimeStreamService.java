package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.dto.stream.OneTimeStreamDTO;
import io.kneo.broadcaster.dto.stream.StreamScheduleDTO;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.SceneScheduleEntry;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.model.stream.StreamSchedule;
import io.kneo.broadcaster.repository.BrandRepository;
import io.kneo.broadcaster.repository.OneTimeStreamRepository;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
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
    BroadcasterConfig broadcasterConfig;


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

    private Uni<OneTimeStreamDTO> mapToDTO(OneTimeStream doc) {
        OneTimeStreamDTO dto = new OneTimeStreamDTO();
        dto.setId(doc.getId());
        dto.setBaseBrandId(doc.getMasterBrand().getId());
        dto.setAiAgentId(doc.getAiAgentId());
        dto.setProfileId(doc.getProfileId());
        dto.setScripts(doc.getScripts());
        dto.setSlugName(doc.getSlugName());
        dto.setUserVariables(doc.getUserVariables());
        dto.setLocalizedName(doc.getLocalizedName());
        dto.setTimeZone(doc.getTimeZone() != null ? doc.getTimeZone().getId() : null);
        dto.setBitRate(doc.getBitRate());
        dto.setStreamSchedule(toScheduleDTO(doc.getStreamSchedule()));
        dto.setCreatedAt(doc.getCreatedAt());
        dto.setExpiresAt(doc.getExpiresAt());
        try {
            dto.setHlsUrl(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.m3u8").toURL());
            dto.setIceCastUrl(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/icecast").toURL());
            dto.setMp3Url(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.mp3").toURL());
            dto.setMixplaUrl(URI.create("https://player.mixpla.io/?radio=" + dto.getSlugName()).toURL());
        } catch (
                MalformedURLException e) {
            throw new RuntimeException(e);
        }
        return Uni.createFrom().item(dto);
    }

    public Uni<OneTimeStream> run(OneTimeStreamRunReqDTO dto, IUser user) {
        return brandRepository.findById(dto.getBaseBrandId(), user, true)
                .chain(sourceBrand -> scriptRepository.findById(dto.getScriptId(), user, false)
                        .chain(script -> {
                            OneTimeStream stream = new OneTimeStream(sourceBrand, script, dto.getUserVariables());
                            stream.setStreamSchedule(fromScheduleDTO(dto.getSchedule()));
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

    public Uni<OneTimeStream> getBySlugName(String slugName) {
        return oneTimeStreamRepository.getBySlugName(slugName);
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

        List<StreamScheduleDTO.SceneScheduleDTO> sceneDTOs = schedule.getSceneScheduleEntries().stream()
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
}
