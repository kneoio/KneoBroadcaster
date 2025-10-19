package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ScriptDTO;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.repository.ScriptRepository;
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
public class ScriptService extends AbstractService<Script, ScriptDTO> {
    private final ScriptRepository repository;

    @Inject
    public ScriptService(UserService userService, ScriptRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<ScriptDTO>> getAll(final int limit, final int offset, final IUser user) {
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<ScriptDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        return repository.getAllCount(user, false);
    }

    @Override
    public Uni<ScriptDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<ScriptDTO> upsert(String id, ScriptDTO dto, IUser user) {
        Script entity = buildEntity(dto);
        if (id == null) {
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

    private Uni<ScriptDTO> mapToDTO(Script script) {
        return Uni.combine().all().unis(
                userService.getUserName(script.getAuthor()),
                userService.getUserName(script.getLastModifier())
        ).asTuple().map(tuple -> {
            ScriptDTO dto = new ScriptDTO();
            dto.setId(script.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(script.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(script.getLastModifiedDate());
            dto.setName(script.getName());
            dto.setDescription(script.getDescription());
            dto.setLabels(script.getLabels());
            return dto;
        });
    }

    private Script buildEntity(ScriptDTO dto) {
        Script entity = new Script();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setLabels(dto.getLabels());
        return entity;
    }

    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList ->
                        accessInfoList.stream()
                                .map(this::mapToDocumentAccessDTO)
                                .collect(Collectors.toList())
                );
    }
}
