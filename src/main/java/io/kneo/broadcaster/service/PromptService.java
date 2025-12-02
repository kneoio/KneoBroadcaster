package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.PromptDTO;
import io.kneo.broadcaster.dto.filter.PromptFilterDTO;
import io.kneo.broadcaster.model.aiagent.Prompt;
import io.kneo.broadcaster.repository.prompt.PromptRepository;
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
public class PromptService extends AbstractService<Prompt, PromptDTO> {
    private final PromptRepository repository;

    @Inject
    public PromptService(UserService userService, PromptRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<PromptDTO>> getAll(final int limit, final int offset, final IUser user, final PromptFilterDTO filter) {
        return repository.getAll(limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<PromptDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user, final PromptFilterDTO filter) {
        return repository.getAllCount(user, false, filter);
    }

    public Uni<Prompt> getById(UUID id, IUser user) {
        return repository.findById(id, user, false);
    }

    public Uni<List<Prompt>> getByIds(List<UUID> ids, IUser user) {
        return repository.findByIds(ids, user);
    }

    @Override
    public Uni<PromptDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<PromptDTO> upsert(String id, PromptDTO dto, IUser user) {
        Prompt entity = buildEntity(dto);
        if (id == null) {
            return repository.insert(entity, user).chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity, user).chain(this::mapToDTO);
        }
    }

    public Uni<Prompt> insert(Prompt entity, IUser user) {
        return repository.insert(entity, user);
    }

    public Uni<Prompt> update(UUID id, Prompt entity, IUser user) {
        return repository.update(id, entity, user);
    }

    public Uni<Integer> archive(String id, IUser user) {
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return repository.delete(UUID.fromString(id), user);
    }

    public Uni<Prompt> findByMasterAndLanguage(UUID masterId, LanguageCode languageCode, boolean includeArchived) {
        return repository.findByMasterAndLanguage(masterId, languageCode, includeArchived);
    }

    private Uni<PromptDTO> mapToDTO(Prompt doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            PromptDTO dto = new PromptDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setEnabled(doc.isEnabled());
            dto.setPrompt(doc.getPrompt());
            dto.setLanguageCode(doc.getLanguageCode());
            dto.setMaster(doc.isMaster());
            dto.setLocked(doc.isLocked());
            dto.setTitle(doc.getTitle());
            dto.setBackup(doc.getBackup());
            dto.setPodcast(doc.isPodcast());
            dto.setDraftId(doc.getDraftId());
            dto.setMasterId(doc.getMasterId());
            dto.setVersion(doc.getVersion());
            return dto;
        });
    }

    private Prompt buildEntity(PromptDTO dto) {
        Prompt doc = new Prompt();
        doc.setId(dto.getId());
        doc.setEnabled(dto.isEnabled());
        doc.setPrompt(dto.getPrompt());
        doc.setLanguageCode(dto.getLanguageCode());
        doc.setMaster(dto.isMaster());
        doc.setLocked(dto.isLocked());
        doc.setTitle(dto.getTitle());
        doc.setBackup(dto.getBackup());
        doc.setPodcast(dto.isPodcast());
        doc.setDraftId(dto.getDraftId());
        if (dto.isMaster()) {
            doc.setMasterId(null);
        } else {
            doc.setMasterId(dto.getMasterId());
        }
        doc.setVersion(dto.getVersion());
        return doc;
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
