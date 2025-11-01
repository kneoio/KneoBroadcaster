package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.DraftDTO;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.repository.DraftRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class DraftService extends AbstractService<Draft, DraftDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftService.class);

    private final DraftRepository repository;

    @Inject
    public DraftService(UserService userService, DraftRepository repository) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<Draft>> getAll() {
        assert repository != null;
        return repository.getAll(0, 0, false, null);
    }

    public Uni<List<DraftDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<DraftDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user, false);
    }

    public Uni<Draft> getById(UUID id, IUser user) {
        return repository.findById(id, user, true);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<DraftDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<DraftDTO> upsert(String id, DraftDTO dto, IUser user, LanguageCode code) {
        assert repository != null;
        Draft entity = buildEntity(dto);

        Uni<Draft> saveOperation;
        if (id == null) {
            saveOperation = repository.insert(entity, user);
        } else {
            saveOperation = repository.update(UUID.fromString(id), entity, user);
        }

        return saveOperation.chain(this::mapToDTO);
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    private Uni<DraftDTO> mapToDTO(Draft doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            DraftDTO dto = new DraftDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setDraftType(doc.getDraftType());
            dto.setTitle(doc.getTitle());
            dto.setContent(doc.getContent());
            dto.setLanguageCode(doc.getLanguageCode());
            dto.setArchived(doc.getArchived());
            return dto;
        });
    }

    private Draft buildEntity(DraftDTO dto) {
        Draft doc = new Draft();
        doc.setDraftType(dto.getDraftType());
        doc.setTitle(dto.getTitle());
        doc.setContent(dto.getContent());
        doc.setLanguageCode(dto.getLanguageCode());
        doc.setArchived(dto.getArchived() != null ? dto.getArchived() : 0);
        return doc;
    }
}
