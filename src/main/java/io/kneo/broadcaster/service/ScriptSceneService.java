package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ScriptSceneDTO;
import io.kneo.broadcaster.dto.ai.PromptDTO;
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
    private final PromptService promptService;

    @Inject
    public ScriptSceneService(UserService userService, ScriptSceneRepository repository, PromptService promptService) {
        super(userService);
        this.repository = repository;
        this.promptService = promptService;
    }

    public Uni<List<ScriptSceneDTO>> getForScript(final UUID scriptId, final int limit, final int offset, final IUser user) {
        return repository.listByScript(scriptId, limit, offset, false, user)
                .chain(list -> {
                    List<Uni<ScriptSceneDTO>> unis = list.stream().map(scene -> mapToDTO(scene, user)).collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getForScriptCount(final UUID scriptId, final IUser user) {
        return repository.countByScript(scriptId, false, user);
    }

    @Override
    public Uni<ScriptSceneDTO> getDTO(UUID id, IUser user, io.kneo.core.localization.LanguageCode language) {
        return repository.findById(id, user, false).chain(scene -> mapToDTO(scene, user));
    }

    public Uni<ScriptSceneDTO> upsert(String id, UUID scriptId, ScriptSceneDTO dto, IUser user) {
        ScriptScene entity = buildEntity(dto);
        if (id == null) {
            entity.setScriptId(scriptId);
            return repository.insert(entity, user).chain(scene -> mapToDTO(scene, user));
        } else {
            return repository.update(UUID.fromString(id), entity, user).chain(scene -> mapToDTO(scene, user));
        }
    }

    public Uni<Integer> archive(String id, IUser user) {
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return repository.delete(UUID.fromString(id), user);
    }

    private Uni<ScriptSceneDTO> mapToDTO(ScriptScene doc, IUser user) {
        Uni<List<PromptDTO>> promptsUni;
        if (doc.getPrompts() == null || doc.getPrompts().isEmpty()) {
            promptsUni = Uni.createFrom().item(List.of());
        } else {
            List<Uni<PromptDTO>> promptUnis = doc.getPrompts().stream()
                    .map(promptId -> promptService.getDTO(promptId, user, io.kneo.core.localization.LanguageCode.en))
                    .collect(Collectors.toList());
            promptsUni = Uni.join().all(promptUnis).andFailFast();
        }

        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                promptsUni
        ).asTuple().map(tuple -> {
            ScriptSceneDTO dto = new ScriptSceneDTO();
            dto.setId(doc.getId());
            dto.setTitle(doc.getTitle());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setScriptId(doc.getScriptId());
            dto.setType(doc.getType());
            dto.setStartTime(doc.getStartTime());
            dto.setWeekdays(doc.getWeekdays());
            dto.setPrompts(tuple.getItem3());
            return dto;
        });
    }

    private ScriptScene buildEntity(ScriptSceneDTO dto) {
        ScriptScene entity = new ScriptScene();
        entity.setType(dto.getType());
        entity.setTitle(dto.getTitle());
        entity.setStartTime(dto.getStartTime());
        entity.setWeekdays(dto.getWeekdays());
        if (dto.getPrompts() == null) {
            entity.setPrompts(List.of());
        } else {
            entity.setPrompts(dto.getPrompts().stream().map(PromptDTO::getId).collect(Collectors.toList()));
        }
        return entity;
    }

    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList -> accessInfoList.stream().map(this::mapToDocumentAccessDTO).collect(Collectors.toList()));
    }
}
