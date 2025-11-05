package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ScriptSceneDTO;
import io.kneo.broadcaster.model.ScriptScene;
import io.kneo.broadcaster.repository.ScriptSceneRepository;
import io.kneo.core.dto.DocumentAccessDTO;
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
public class ScriptSceneService extends AbstractService<ScriptScene, ScriptSceneDTO> {
    private final ScriptSceneRepository repository;

    @Inject
    public ScriptSceneService(UserService userService, ScriptSceneRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<ScriptSceneDTO>> getAll(final UUID scriptId, final int limit, final int offset, final IUser user) {
        return repository.listByScript(scriptId, limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<ScriptSceneDTO>> unis = list.stream().map(this::mapToDTO).collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<List<ScriptScene>> getAllWithPromptIds(final UUID scriptId, final int limit, final int offset, final IUser user) {
        return repository.listByScript(scriptId, limit, offset, false, user);
    }

    public Uni<Integer> getAllCount(final UUID scriptId, final IUser user) {
        return repository.countByScript(scriptId, false, user);
    }

    @Override
    public Uni<ScriptSceneDTO> getDTO(UUID id, IUser user, io.kneo.core.localization.LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<ScriptSceneDTO> upsert(String id, UUID scriptId, ScriptSceneDTO dto, IUser user) {
        ScriptScene entity = buildEntity(dto);
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

    private Uni<ScriptSceneDTO> mapToDTO(ScriptScene doc) {
        return mapToDTO(doc, true);
    }

    private Uni<ScriptSceneDTO> mapToDTO(ScriptScene doc, boolean includePrompts) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            ScriptSceneDTO dto = new ScriptSceneDTO();
            dto.setId(doc.getId());
            dto.setTitle(doc.getTitle());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setScriptId(doc.getScriptId());
            dto.setStartTime(doc.getStartTime());
            dto.setOneTimeRun(doc.isOneTimeRun());
            dto.setWeekdays(doc.getWeekdays());
            dto.setPrompts(includePrompts ? doc.getPrompts() : null);
            return dto;
        });
    }

    private ScriptScene buildEntity(ScriptSceneDTO dto) {
        ScriptScene entity = new ScriptScene();
        entity.setTitle(dto.getTitle());
        entity.setStartTime(dto.getStartTime());
        entity.setOneTimeRun(dto.isOneTimeRun());
        entity.setWeekdays(dto.getWeekdays());
        entity.setPrompts(dto.getPrompts() != null ? dto.getPrompts() : List.of());
        return entity;
    }

    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList -> accessInfoList.stream().map(this::mapToDocumentAccessDTO).collect(Collectors.toList()));
    }
}
