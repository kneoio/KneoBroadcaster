package io.kneo.broadcaster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.dto.GenreDTO;
import io.kneo.broadcaster.dto.aiagent.VoiceDTO;
import io.kneo.broadcaster.model.Genre;
import io.kneo.broadcaster.repository.GenreRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.IRESTService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class RefService extends AbstractService<Genre, GenreDTO> implements IRESTService<GenreDTO> {
    private final GenreRepository repository;
    private final ObjectMapper objectMapper;

    @Inject
    public RefService(UserService userService, GenreRepository repository) {
        super(userService);
        this.repository = repository;
        this.objectMapper = new ObjectMapper();
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

    public Uni<List<UUID>> resolveGenresByName(String genreString) {
        if (genreString == null || genreString.trim().isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        String[] genreNames = genreString.split(",");
        List<Uni<UUID>> genreUnis = Stream.of(genreNames)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(name -> repository.findByFuzzyIdentifier(name)
                        .map(genres -> genres.isEmpty() ? null : genres.get(0).getId())
                )
                .collect(Collectors.toList());

        return Uni.join().all(genreUnis).andFailFast()
                .map(list -> list.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList())
                );
    }

    @Override
    public Uni<GenreDTO> getDTO(UUID uuid, IUser user, LanguageCode language) {
        return repository.findById(uuid).chain(this::mapToDTO);
    }

    public Uni<List<VoiceDTO>> getAllVoices() {
        return Uni.createFrom().item(() -> {
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("voices.json")) {
                if (inputStream == null) {
                    throw new RuntimeException("voices.json file not found in resources");
                }
                return objectMapper.readValue(inputStream, new TypeReference<List<VoiceDTO>>() {});
            } catch (IOException e) {
                throw new RuntimeException("Error reading voices.json", e);
            }
        });
    }

    public Uni<Integer> getAllVoicesCount() {
        return getAllVoices().map(List::size);
    }

    private Uni<GenreDTO> mapToDTO(Genre doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
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