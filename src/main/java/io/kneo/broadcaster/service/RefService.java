package io.kneo.broadcaster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.dto.VoiceFilterDTO;
import io.kneo.broadcaster.dto.aiagent.VoiceDTO;
import io.kneo.broadcaster.model.aiagent.TTSEngineType;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.officeframe.dto.GenreDTO;
import io.kneo.officeframe.dto.LabelDTO;
import io.kneo.officeframe.service.GenreService;
import io.kneo.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class RefService {
    private final LabelService labelService;
    private final GenreService genreService;
    private final ObjectMapper objectMapper;

    @Inject
    public RefService(LabelService labelService, GenreService genreService) {
        this.labelService = labelService;
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

    public Uni<List<LabelDTO>> getSoundFragmentLabels() {
        return labelService.getOfCategory("sound_fragment", LanguageCode.en);
    }
}
