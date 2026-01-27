package io.kneo.broadcaster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.dto.LabelDTO;
import io.kneo.broadcaster.dto.VoiceFilterDTO;
import io.kneo.broadcaster.dto.aiagent.VoiceDTO;
import io.kneo.broadcaster.model.Genre;
import io.kneo.broadcaster.model.Label;
import io.kneo.broadcaster.model.aiagent.TTSEngineType;
import io.kneo.broadcaster.repository.LabelRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.UserService;
import io.kneo.officeframe.dto.GenreDTO;
import io.kneo.officeframe.service.GenreService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class RefService {
    private final UserService userService;
    private final LabelRepository labelRepository;
    private final GenreService genreService;
    private final ObjectMapper objectMapper;

    @Inject
    public RefService(UserService userService, LabelRepository labelRepository, GenreService genreService) {
        this.userService = userService;
        this.labelRepository = labelRepository;
        this.genreService = genreService;
        this.objectMapper = new ObjectMapper();
    }

    public Uni<List<VoiceDTO>> getAllVoices(TTSEngineType engineType) {
        return Uni.createFrom().item(() -> {
            String fileName = engineType.getValue() + "-voices.json";
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(fileName)) {
                if (inputStream == null) {
                    throw new RuntimeException(fileName + " file not found in resources");
                }
                return objectMapper.readValue(inputStream, new TypeReference<>() {});
            } catch (IOException e) {
                throw new RuntimeException("Error reading " + fileName, e);
            }
        });
    }

    public Uni<Integer> getAllGenresCount() {
        return genreService.getAllCount(SuperUser.build());
    }

    public Uni<List<GenreDTO>> getAllGenres(final int limit, final int offset) {
        return genreService.getAll(limit, offset,null, LanguageCode.en);
    }

    public Uni<Integer> getAllVoicesCount(TTSEngineType engineType) {
        return getAllVoices(engineType).map(List::size);
    }

    public Uni<List<VoiceDTO>> getFilteredVoices(VoiceFilterDTO filter) {
        TTSEngineType engineType = filter.getEngineType() != null ? filter.getEngineType() : TTSEngineType.ELEVENLABS;
        return getAllVoices(engineType).map(voices -> voices.stream()
                .filter(voice -> {
                    if (filter.getGender() != null && !filter.getGender().isEmpty() 
                            && !filter.getGender().equalsIgnoreCase(voice.getGender())) {
                        return false;
                    }
                    if (filter.getLanguages() != null && !filter.getLanguages().isEmpty() 
                            && !filter.getLanguages().contains(voice.getLanguage())) {
                        return false;
                    }
                    if (filter.getLabels() != null && !filter.getLabels().isEmpty()) {
                        boolean hasAllLabels = filter.getLabels().stream()
                                .allMatch(label -> voice.getLabels() != null && voice.getLabels().contains(label));
                        if (!hasAllLabels) {
                            return false;
                        }
                    }
                    if (filter.getSearchTerm() != null && !filter.getSearchTerm().isEmpty()) {
                        String searchTerm = filter.getSearchTerm().toLowerCase();
                        if (voice.getName() == null || !voice.getName().toLowerCase().contains(searchTerm)) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList()));
    }

    public Uni<Integer> getFilteredVoicesCount(VoiceFilterDTO filter) {
        return getFilteredVoices(filter).map(List::size);
    }

      @Deprecated
    public Uni<? extends Optional<Genre>> findById(UUID Genre) {
        return null;
    }

    public Uni<List<LabelDTO>> getAllLabels(final int limit, final int offset) {
        return labelRepository.getAll(limit, offset)
                .chain(list -> Uni.join().all(
                        list.stream()
                                .map(this::mapLabelToDTO)
                                .collect(Collectors.toList())
                ).andFailFast());
    }

    public Uni<Integer> getAllLabelsCount() {
        return labelRepository.getAllCount();
    }

    private Uni<LabelDTO> mapLabelToDTO(Label doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().onItem().transform(tuple ->
                LabelDTO.builder()
                        .id(doc.getId())
                        .author(tuple.getItem1())
                        .regDate(doc.getRegDate())
                        .lastModifier(tuple.getItem2())
                        .lastModifiedDate(doc.getLastModifiedDate())
                        .identifier(doc.getIdentifier())
                        .localizedName(doc.getLocalizedName())
                        .slugName(doc.getSlugName())
                        .color(doc.getColor())
                        .archived(doc.getArchived())
                        .build()
        );
    }
}
