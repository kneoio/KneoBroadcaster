package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.SceneDTO;
import io.kneo.broadcaster.dto.ScenePromptDTO;
import io.kneo.broadcaster.dto.StagePlaylistDTO;
import io.kneo.broadcaster.dto.filter.SceneFilterDTO;
import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.repository.SceneRepository;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SceneService extends AbstractService<Scene, SceneDTO> {
    private final SceneRepository repository;

    @Inject
    public SceneService(UserService userService, SceneRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<SceneDTO>> getAllDTO(final int limit, final int offset, final IUser user, SceneFilterDTO filter) {
        return repository.getAll(limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<SceneDTO>> unis = list.stream().map(this::mapToDTO).collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getAllCount(final IUser user, SceneFilterDTO filter) {
        return repository.getAllCount(user, false, filter);
    }

    public Uni<List<Scene>> getAllWithPromptIds(final UUID scriptId, final int limit, final int offset, final IUser user) {
        return repository.listByScript(scriptId, limit, offset, false, user);
    }

    public Uni<List<SceneDTO>> getAllByScript(final UUID scriptId, final int limit, final int offset, final IUser user) {
        return repository.listByScript(scriptId, limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<SceneDTO>> unis = list.stream().map(this::mapToDTO).collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }


    public Uni<Integer> getByScriptCount(final UUID scriptId, final IUser user) {
        return repository.countByScript(scriptId, false, user);
    }

    @Override
    public Uni<SceneDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<Scene> getById(UUID sceneId, IUser user) {
        return repository.findById(sceneId, user, false);
    }

    public Uni<SceneDTO> upsert(String id, UUID scriptId, SceneDTO dto, IUser user) {
        Scene entity = buildEntity(dto);
        if (id == null) {
            entity.setScriptId(scriptId);
            return repository.insert(entity, user).chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity, user).chain(this::mapToDTO);
        }
    }

    public Uni<Integer> archive(String id, IUser user) {
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return repository.delete(UUID.fromString(id), user);
    }

    private Uni<SceneDTO> mapToDTO(Scene doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            SceneDTO dto = new SceneDTO();
            dto.setId(doc.getId());
            dto.setTitle(doc.getTitle());
            dto.setScriptTitle(doc.getScriptTitle());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setScriptId(doc.getScriptId());
            dto.setStartTime(doc.getStartTime());
            dto.setDurationSeconds(doc.getDurationSeconds());
            dto.setSeqNum(doc.getSeqNum());
            dto.setTalkativity(doc.getTalkativity());
            dto.setWeekdays(doc.getWeekdays());
            dto.setPrompts(mapScenePromptsToDTOs(doc.getPrompts()));
            dto.setStagePlaylist(mapStagePlaylistToDTO(doc.getPlaylistRequest()));
            return dto;
        });
    }

    private List<ScenePromptDTO> mapScenePromptsToDTOs(List<ScenePrompt> livePrompts) {
        if (livePrompts == null) {
            return null;
        }
        return livePrompts.stream()
                .map(sp -> {
                    ScenePromptDTO dto = new ScenePromptDTO();
                    dto.setPromptId(sp.getPromptId());
                    dto.setRank(sp.getRank());
                    dto.setWeight(sp.getWeight());
                    dto.setActive(sp.isActive());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private Scene buildEntity(SceneDTO dto) {
        Scene entity = new Scene();
        entity.setTitle(dto.getTitle());
        entity.setStartTime(dto.getStartTime());
        entity.setDurationSeconds(dto.getDurationSeconds());
        entity.setSeqNum(dto.getSeqNum());
        entity.setWeekdays(dto.getWeekdays());
        entity.setTalkativity(dto.getTalkativity());
        entity.setPrompts(dto.getPrompts() != null ? mapScenePromptDTOsToEntities(dto.getPrompts()) : List.of());
        entity.setPlaylistRequest(mapDTOToStagePlaylist(dto.getStagePlaylist()));
        return entity;
    }

    private List<ScenePrompt> mapScenePromptDTOsToEntities(List<ScenePromptDTO> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(dto -> {
                    ScenePrompt sp = new ScenePrompt();
                    sp.setPromptId(dto.getPromptId());
                    sp.setRank(dto.getRank());
                    sp.setWeight(dto.getWeight());
                    sp.setActive(dto.isActive());
                    return sp;
                })
                .collect(Collectors.toList());
    }

    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList -> accessInfoList.stream().map(this::mapToDocumentAccessDTO).collect(Collectors.toList()));
    }

    private StagePlaylistDTO mapStagePlaylistToDTO(PlaylistRequest playlistRequest) {
        if (playlistRequest == null) {
            return null;
        }
        StagePlaylistDTO dto = new StagePlaylistDTO();
        dto.setSourcing(playlistRequest.getSourcing() != null ? playlistRequest.getSourcing().name() : null);
        dto.setTitle(playlistRequest.getTitle());
        dto.setArtist(playlistRequest.getArtist());
        dto.setGenres(playlistRequest.getGenres());
        dto.setLabels(playlistRequest.getLabels());
        dto.setType(playlistRequest.getType() != null ? playlistRequest.getType().stream().map(Enum::name).toList() : null);
        dto.setSource(playlistRequest.getSource() != null ? playlistRequest.getSource().stream().map(Enum::name).toList() : null);
        dto.setSearchTerm(playlistRequest.getSearchTerm());
        dto.setSoundFragments(playlistRequest.getSoundFragments());
        dto.setPrompts(mapScenePromptsToDTOs(playlistRequest.getPrompts()));
        return dto;
    }

    private PlaylistRequest mapDTOToStagePlaylist(StagePlaylistDTO dto) {
        if (dto == null) {
            return null;
        }
        PlaylistRequest playlistRequest = new PlaylistRequest();
        playlistRequest.setSourcing(dto.getSourcing() != null ? WayOfSourcing.valueOf(dto.getSourcing()) : null);
        playlistRequest.setTitle(dto.getTitle());
        playlistRequest.setArtist(dto.getArtist());
        playlistRequest.setGenres(dto.getGenres());
        playlistRequest.setLabels(dto.getLabels());
        playlistRequest.setType(dto.getType() != null ? dto.getType().stream().map(PlaylistItemType::valueOf).toList() : null);
        playlistRequest.setSource(dto.getSource() != null ? dto.getSource().stream().map(SourceType::valueOf).toList() : null);
        playlistRequest.setSearchTerm(dto.getSearchTerm());
        playlistRequest.setSoundFragments(dto.getSoundFragments());
        playlistRequest.setPrompts(dto.getPrompts() != null ? mapScenePromptDTOsToEntities(dto.getPrompts()) : List.of());
        return playlistRequest;
    }
}
