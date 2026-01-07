package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.dto.stream.OneTimeStreamDTO;
import io.kneo.broadcaster.dto.stream.StreamScheduleDTO;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.cnst.StreamStatus;
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
import io.kneo.broadcaster.service.stream.StreamScheduleService;
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
import java.util.Map;
import java.util.UUID;

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

    @Inject
    BrandService brandService;

    @Inject
    StreamScheduleService streamScheduleService;


    public Uni<OneTimeStreamRunReqDTO> populateFromSlugName(OneTimeStreamRunReqDTO dto, IUser user) {
        if (dto.getSlugName() == null || dto.getSlugName().isEmpty()) {
            return Uni.createFrom().item(dto);
        }

        return brandService.getBySlugName(dto.getSlugName())
                .chain(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + dto.getSlugName()));
                    }

                    dto.setBaseBrandId(brand.getId());
                    dto.setAiAgentId(brand.getAiAgentId());

                    return scriptRepository.findById(dto.getScriptId(), user, false)
                            .chain(script -> {
                                if (script == null) {
                                    return Uni.createFrom().failure(new IllegalArgumentException("Script not found"));
                                }

                                dto.setProfileId(script.getDefaultProfileId());

                                if (dto.getSchedule() == null) {
                                    return streamScheduleService.getStreamScheduleDTO(brand.getId(), script.getId(), user)
                                            .map(schedule -> {
                                                dto.setSchedule(schedule);
                                                return dto;
                                            });
                                }

                                return Uni.createFrom().item(dto);
                            });
                });
    }

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
        dto.setStreamSchedule(streamScheduleService.toScheduleDTO(doc.getStreamSchedule()));
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
        
        return radioStationPool.getLiveStatus(doc.getSlugName())
                .onItem().invoke(liveStatus -> dto.setStatus(liveStatus.getStatus()))
                .replaceWith(dto);
    }

    public Uni<OneTimeStream> start(OneTimeStream stream) {
        LOGGER.info("OneTimeStream: Initializing stream slugName={}", stream.getSlugName());
        String streamSlugName = stream.getSlugName();
        return radioStationPool.initializeStream(stream)
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to initialize stream: {}", streamSlugName, failure);
                    radioStationPool.get(streamSlugName)
                            .subscribe().with(
                                    station -> {
                                        if (station != null) {
                                            station.setStatus(StreamStatus.SYSTEM_ERROR);
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
    }

    public Uni<OneTimeStream> run(OneTimeStreamRunReqDTO dto, IUser user) {
        return brandRepository.findById(dto.getBaseBrandId(), user, true)
                .chain(sourceBrand -> scriptRepository.findById(dto.getScriptId(), user, false)
                        .chain(script -> {
                            OneTimeStream stream = new OneTimeStream(sourceBrand, script, dto.getUserVariables());
                            stream.setAiAgentId(dto.getAiAgentId());
                            stream.setProfileId(dto.getProfileId());
                            stream.setStreamSchedule(fromScheduleDTO(dto.getSchedule()));
                            if (!dto.isStartImmediately()) {
                                stream.setStatus(StreamStatus.PENDING);
                            }
                            oneTimeStreamRepository.insert(stream);
                            LOGGER.info("OneTimeStream created: slugName={}, id={}, status={}", stream.getSlugName(), stream.getId(), stream.getStatus());
                            return Uni.createFrom().item(stream);
                        })
                )
                .chain(stream -> dto.isStartImmediately() ? start(stream) : Uni.createFrom().item(stream));
    }

    public Uni<OneTimeStream> getBySlugName(String slugName) {
        return oneTimeStreamRepository.getBySlugName(slugName);
    }

    public Uni<Void> delete(UUID id) {
        return oneTimeStreamRepository.findById(id)
                .chain(stream -> {
                    if (stream == null) {
                        return Uni.createFrom().failure(new RuntimeException("Stream not found"));
                    }
                    return radioStationPool.stopAndRemove(stream.getSlugName())
                            .chain(() -> oneTimeStreamRepository.delete(id));
                });
    }

    public Uni<OneTimeStreamDTO> upsert(String id, OneTimeStreamDTO dto, IUser user, LanguageCode languageCode) {
        return brandRepository.findById(dto.getBaseBrandId(), user, true)
                .chain(sourceBrand -> {
                    UUID scriptId = dto.getScripts() != null && !dto.getScripts().isEmpty() 
                            ? dto.getScripts().getFirst().getScriptId()
                            : null;
                    
                    if (scriptId == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Script ID is required"));
                    }
                    
                    return scriptRepository.findById(scriptId, user, false)
                            .chain(script -> {
                                OneTimeStream stream;
                                Map<String, Object> userVariables = dto.getUserVariables();
                                
                                if (id == null) {
                                    stream = new OneTimeStream(sourceBrand, script, userVariables);
                                    stream.setAiAgentId(dto.getAiAgentId());
                                    stream.setProfileId(dto.getProfileId());
                                    stream.setStreamSchedule(fromScheduleDTO(dto.getStreamSchedule()));
                                    oneTimeStreamRepository.insert(stream);
                                    return mapToDTO(stream);
                                } else {
                                    return oneTimeStreamRepository.findById(UUID.fromString(id))
                                            .chain(existing -> {
                                                if (existing == null) {
                                                    return Uni.createFrom().failure(new RuntimeException("Stream not found"));
                                                }
                                                existing.setMasterBrand(sourceBrand);
                                                existing.setScript(script);
                                                existing.setUserVariables(userVariables);
                                                existing.setAiAgentId(dto.getAiAgentId());
                                                existing.setProfileId(dto.getProfileId());
                                                existing.setStreamSchedule(fromScheduleDTO(dto.getStreamSchedule()));
                                                return oneTimeStreamRepository.update(UUID.fromString(id), existing)
                                                        .chain(this::mapToDTO);
                                            });
                                }
                            });
                });
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
        StreamScheduleDTO.ScenePlaylistRequest request = dto.getPlaylistRequest();
        SceneScheduleEntry entry = new SceneScheduleEntry(
                UUID.fromString(dto.getSceneId()),
                dto.getSceneTitle(),
                dto.getScheduledStartTime(),
                dto.getDurationSeconds(),
                dto.getOriginalStartTime(),
                dto.getOriginalEndTime(),
                request != null && request.getSourcing() != null ? WayOfSourcing.valueOf(request.getSourcing()) : null,
                request != null ? request.getPlaylistTitle() : null,
                request != null ? request.getArtist() : null,
                request != null ? request.getGenres() : null,
                request != null ? request.getLabels() : null,
                request != null && request.getPlaylistItemTypes() != null ? request.getPlaylistItemTypes().stream().map(PlaylistItemType::valueOf).toList() : null,
                request != null && request.getSourceTypes() != null ? request.getSourceTypes().stream().map(SourceType::valueOf).toList() : null,
                request != null ? request.getSearchTerm() : null,
                request != null ? request.getSoundFragments() : null
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
                dto.getScheduledStartTime(),
                dto.getEstimatedDurationSeconds()
        );
    }
}
