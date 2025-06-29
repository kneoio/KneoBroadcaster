package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.GenreDTO;
import io.kneo.broadcaster.model.Genre;
import io.kneo.broadcaster.repository.GenreRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.UserRepository;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.IRESTService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class GenreService extends AbstractService<Genre, GenreDTO> implements IRESTService<GenreDTO> {
    private final GenreRepository repository;

    @Inject
    public GenreService(UserRepository userRepository, UserService userService, GenreRepository repository) {
        super(userRepository, userService);
        this.repository = repository;
    }

    public Uni<List<GenreDTO>> getAll(final int limit, final int offset, LanguageCode languageCode) {
        return repository.getAll(limit, offset)
                .chain(list -> Uni.join().all(
                        list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList())
                ).andFailFast());
    }

    @Override
    public Uni<GenreDTO> getDTOByIdentifier(String identifier) {
        return repository.findByIdentifier(identifier).chain(this::mapToDTO);
    }

    public Uni<Integer> getAllCount(IUser user) {
        return repository.getAllCount();
    }

    public Uni<Genre> getById(UUID uuid) {
        return repository.findById(uuid);
    }

    public Uni<Genre> getByIdentifier(String uuid) {
        return repository.findByIdentifier(uuid);
    }

    @Override
    public Uni<GenreDTO> getDTO(UUID uuid, IUser user, LanguageCode language) {
        return repository.findById(uuid).chain(this::mapToDTO);
    }

    private Uni<GenreDTO> mapToDTO(Genre doc) {
        return Uni.combine().all().unis(
                userRepository.getUserName(doc.getAuthor()),
                userRepository.getUserName(doc.getLastModifier())
        ).asTuple().onItem().transform(tuple ->
                GenreDTO.builder()
                        .id(doc.getId())
                        .author(tuple.getItem1())
                        .regDate(doc.getRegDate())
                        .lastModifier(tuple.getItem2())
                        .lastModifiedDate(doc.getLastModifiedDate())
                        .identifier(doc.getIdentifier())
                        .localizedName(doc.getLocalizedName())
                        .build()
        );
    }

    @Override
    public Uni<GenreDTO> upsert(String id, GenreDTO dto, IUser user, LanguageCode code) {
        return null;
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) throws DocumentModificationAccessException {
        return null;
    }

    @Deprecated
    public Uni<? extends Optional<Genre>> findById(UUID Genre) {
        return null;
    }
}