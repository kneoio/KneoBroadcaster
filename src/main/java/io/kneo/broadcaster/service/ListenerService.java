package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.repository.ListenersRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.UserRepository;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListenerService extends AbstractService<Listener, ListenerDTO> {
    private final ListenersRepository repository;
    private final Validator validator;

    protected ListenerService() {
        super(null, null);
        this.repository = null;
        this.validator = null;
    }

    @Inject
    public ListenerService(UserRepository userRepository,
                            UserService userService,
                            Validator validator,
                            ListenersRepository repository) {
        super(userRepository, userService);
        this.validator = validator;
        this.repository = repository;
    }

    public Uni<List<ListenerDTO>> getAll(final int limit, final int offset) {
        assert repository != null;
        return repository.getAll(limit, offset)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<ListenerDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user);
    }

    @Override
    public Uni<ListenerDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;
        user = SuperUser.build();
        return repository.findById(uuid, user.getId())
                .chain(this::mapToDTO);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id));
    }

    public Uni<ListenerDTO> upsert(String id, ListenerDTO dto, IUser user) {
        assert repository != null;
        Listener entity = buildEntity(dto);

        if (id == null) {
            return repository.insert(entity, user.getId())
                    .chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity, user.getId())
                    .chain(this::mapToDTO);
        }
    }

    private Uni<ListenerDTO> mapToDTO(Listener doc) {
        return Uni.combine().all().unis(
                userRepository.getUserName(doc.getAuthor()),
                userRepository.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            ListenerDTO dto = new ListenerDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setCountry(doc.getCountry());
            dto.setSlugName(doc.getSlugName());
            dto.setArchived(doc.getArchived());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setNickName(doc.getNickName());
            return dto;
        });
    }

    private Listener buildEntity(ListenerDTO dto) {
        Listener doc = new Listener();
        doc.setCountry(dto.getCountry());
        doc.setSlugName(dto.getSlugName());
        doc.setArchived(dto.getArchived());
        doc.setLocalizedName(dto.getLocalizedName());
        return doc;
    }

    public Uni<Integer> delete(String id) {
        assert repository != null;
        return repository.delete(UUID.fromString(id));
    }
}
