package io.kneo.broadcaster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.dto.LabelDTO;
import io.kneo.broadcaster.dto.aiagent.VoiceDTO;
import io.kneo.broadcaster.model.Genre;
import io.kneo.broadcaster.model.Label;
import io.kneo.broadcaster.model.aiagent.TTSEngineType;
import io.kneo.broadcaster.repository.LabelRepository;
import io.kneo.core.service.UserService;
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
    private final ObjectMapper objectMapper;

    @Inject
    public RefService(UserService userService, LabelRepository labelRepository) {
        this.userService = userService;
        this.labelRepository = labelRepository;
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

    public Uni<Integer> getAllVoicesCount(TTSEngineType engineType) {
        return getAllVoices(engineType).map(List::size);
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
