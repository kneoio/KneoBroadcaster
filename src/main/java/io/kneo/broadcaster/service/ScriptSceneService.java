package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ScriptSceneDTO;
import io.kneo.broadcaster.dto.ai.PromptDTO;
import io.kneo.broadcaster.model.ScriptScene;
import io.kneo.broadcaster.model.ai.Prompt;
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

    public Uni<List<ScriptSceneDTO>> getForScript(final UUID scriptId, final int limit, final int offset, final IUser user) {
        return repository.listByScript(scriptId, limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<ScriptSceneDTO>> unis = list.stream().map(this::mapToDTO).collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getForScriptCount(final UUID scriptId, final IUser user) {
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

    private Uni<ScriptSceneDTO> mapToDTO(ScriptScene scene) {
        return Uni.combine().all().unis(
                userService.getUserName(scene.getAuthor()),
                userService.getUserName(scene.getLastModifier())
        ).asTuple().map(tuple -> {
            ScriptSceneDTO dto = new ScriptSceneDTO();
            dto.setId(scene.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(scene.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(scene.getLastModifiedDate());
            dto.setScriptId(scene.getScriptId());
            dto.setType(scene.getType());
            dto.setStartTime(scene.getStartTime());
            if (scene.getPrompts() == null) {
                dto.setPrompts(List.of());
            } else {
                dto.setPrompts(scene.getPrompts().stream().map(this::toPromptDTO).collect(Collectors.toList()));
            }
            return dto;
        });
    }

    private ScriptScene buildEntity(ScriptSceneDTO dto) {
        ScriptScene entity = new ScriptScene();
        entity.setType(dto.getType());
        entity.setStartTime(dto.getStartTime());
        if (dto.getPrompts() == null) {
            entity.setPrompts(List.of());
        } else {
            entity.setPrompts(dto.getPrompts().stream().map(this::toPrompt).collect(Collectors.toList()));
        }
        return entity;
    }

    private PromptDTO toPromptDTO(Prompt p) {
        PromptDTO dto = new PromptDTO();
        dto.setEnabled(p.isEnabled());
        dto.setPrompt(p.getPrompt());
        return dto;
    }

    private Prompt toPrompt(PromptDTO dto) {
        Prompt p = new Prompt();
        p.setEnabled(dto.isEnabled());
        p.setPrompt(dto.getPrompt());
        return p;
    }

    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList -> accessInfoList.stream().map(this::mapToDocumentAccessDTO).collect(Collectors.toList()));
    }
}
