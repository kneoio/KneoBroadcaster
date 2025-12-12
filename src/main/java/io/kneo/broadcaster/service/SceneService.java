package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.SceneDTO;
import io.kneo.broadcaster.dto.ScenePromptDTO;
import io.kneo.broadcaster.dto.StagePlaylistDTO;
import io.kneo.broadcaster.model.Action;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.StagePlaylist;
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

    public Uni<List<SceneDTO>> getAll(final int limit, final int offset, final IUser user) {
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<SceneDTO>> unis = list.stream().map(this::mapToDTO).collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
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

    public Uni<Integer> getAllCount(final IUser user) {
        return repository.getAllCount(user, false);
    }

    @Override
    public Uni<SceneDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
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
        return mapToDTO(doc, true);
    }

    private Uni<SceneDTO> mapToDTO(Scene doc, boolean includePrompts) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            SceneDTO dto = new SceneDTO();
            dto.setId(doc.getId());
            dto.setTitle(doc.getTitle());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setScriptId(doc.getScriptId());
            dto.setStartTime(doc.getStartTime());
            dto.setDurationSeconds(doc.getDurationSeconds());
            dto.setSeqNum(doc.getSeqNum());
            dto.setOneTimeRun(doc.isOneTimeRun());
            dto.setTalkativity(doc.getTalkativity());
            dto.setPodcastMode(doc.getPodcastMode());
            dto.setWeekdays(doc.getWeekdays());
            dto.setPrompts(includePrompts ? mapScenePromptsToDTOs(doc.getPrompts()) : null);
            dto.setStagePlaylist(mapStagePlaylistToDTO(doc.getStagePlaylist()));
            return dto;
        });
    }

    private List<ScenePromptDTO> mapScenePromptsToDTOs(List<Action> actions) {
        if (actions == null) {
            return null;
        }
        return actions.stream()
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
        entity.setOneTimeRun(dto.isOneTimeRun());
        entity.setWeekdays(dto.getWeekdays());
        entity.setTalkativity(dto.getTalkativity());
        entity.setPodcastMode(dto.getPodcastMode());
        entity.setPrompts(dto.getPrompts() != null ? mapScenePromptDTOsToEntities(dto.getPrompts()) : List.of());
        entity.setStagePlaylist(mapDTOToStagePlaylist(dto.getStagePlaylist()));
        return entity;
    }

    private List<Action> mapScenePromptDTOsToEntities(List<ScenePromptDTO> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(dto -> {
                    Action sp = new Action();
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

    private StagePlaylistDTO mapStagePlaylistToDTO(StagePlaylist stagePlaylist) {
        if (stagePlaylist == null) {
            return null;
        }
        StagePlaylistDTO dto = new StagePlaylistDTO();
        dto.setSourcing(stagePlaylist.getSourcing() != null ? stagePlaylist.getSourcing().name() : null);
        dto.setTitle(stagePlaylist.getTitle());
        dto.setArtist(stagePlaylist.getArtist());
        dto.setGenres(stagePlaylist.getGenres());
        dto.setLabels(stagePlaylist.getLabels());
        dto.setType(stagePlaylist.getType() != null ? stagePlaylist.getType().stream().map(Enum::name).toList() : null);
        dto.setSource(stagePlaylist.getSource() != null ? stagePlaylist.getSource().stream().map(Enum::name).toList() : null);
        dto.setSearchTerm(stagePlaylist.getSearchTerm());
        dto.setSoundFragments(stagePlaylist.getSoundFragments());
        return dto;
    }

    private StagePlaylist mapDTOToStagePlaylist(StagePlaylistDTO dto) {
        if (dto == null) {
            return null;
        }
        StagePlaylist stagePlaylist = new StagePlaylist();
        stagePlaylist.setSourcing(dto.getSourcing() != null ? WayOfSourcing.valueOf(dto.getSourcing()) : null);
        stagePlaylist.setTitle(dto.getTitle());
        stagePlaylist.setArtist(dto.getArtist());
        stagePlaylist.setGenres(dto.getGenres());
        stagePlaylist.setLabels(dto.getLabels());
        stagePlaylist.setType(dto.getType() != null ? dto.getType().stream().map(PlaylistItemType::valueOf).toList() : null);
        stagePlaylist.setSource(dto.getSource() != null ? dto.getSource().stream().map(SourceType::valueOf).toList() : null);
        stagePlaylist.setSearchTerm(dto.getSearchTerm());
        stagePlaylist.setSoundFragments(dto.getSoundFragments());
        return stagePlaylist;
    }
}
