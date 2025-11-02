package io.kneo.broadcaster.ai;

import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.Profile;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.broadcaster.model.cnst.DraftType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.DraftService;
import io.kneo.broadcaster.service.ProfileService;
import io.kneo.broadcaster.service.RefService;
import io.kneo.broadcaster.template.KotlinTemplateEngine;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class DraftFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftFactory.class);

    private final RefService refService;
    private final ProfileService profileService;
    private final DraftService draftService;
    private final Random random = new Random();
    private final KotlinTemplateEngine kotlinEngine = new KotlinTemplateEngine();

    @Inject
    public DraftFactory(RefService refService, ProfileService profileService, DraftService draftService) {
        this.refService = refService;
        this.profileService = profileService;
        this.draftService = draftService;
    }

    public Uni<String> createDraft(
            PromptType promptType,
            SoundFragment song,
            AiAgent agent,
            RadioStation station,
            JsonObject memoryData
    ) {
        DraftType draftType = mapPromptTypeToDraftType(promptType);
        
        return Uni.combine().all()
                .unis(
                        getDraftTemplate(draftType, agent.getPreferredLang()),
                        profileService.getById(station.getProfileId())
                )
                .asTuple()
                .map(tuple -> {
                    Draft template = tuple.getItem1();
                    Profile profile = tuple.getItem2();

                    if (template != null) {
                        return buildFromTemplate(
                                template.getContent(),
                                song,
                                agent,
                                station,
                                memoryData,
                                profile
                        );
                    } else {
                        String msg = String.format("No draft template found for type=%s, language=%s. Fallbacks are disabled.",
                                draftType, agent.getPreferredLang());
                        LOGGER.error(msg);
                        throw new IllegalStateException(msg);
                    }
                });
    }

    private DraftType mapPromptTypeToDraftType(PromptType promptType) {
        return switch (promptType) {
            case BASIC_INTRO -> DraftType.INTRO_DRAFT;
            case USER_MESSAGE -> DraftType.MESSAGE_DRAFT;
            case NEWS, WEATHER, EVENT -> DraftType.NEWS_INTRO_DRAFT;
        };
    }

    private Uni<Draft> getDraftTemplate(DraftType draftType, io.kneo.core.localization.LanguageCode languageCode) {
        return draftService.getAll()
                .map(drafts -> drafts.stream()
                        .filter(d -> draftType.name().equals(d.getDraftType()) && 
                                    languageCode.equals(d.getLanguageCode()))
                        .findFirst()
                        .orElse(null)
                );
    }

    private String buildFromTemplate(
            String template,
            SoundFragment song,
            AiAgent agent,
            RadioStation station,
            JsonObject memoryData,
            Profile profile
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("song", song);
        data.put("agent", agent);
        data.put("station", station);
        data.put("memoryData", memoryData);
        data.put("profile", profile);
        data.put("random", random); //we will use Java random
        data.put("resolveGenres", (Function<List<UUID>, List<String>>) genreIds ->
                resolveGenreNamesSync(genreIds, agent)
        );
        data.put("extractMemory", (Function<String, List<Map<String, Object>>>) memoryType ->
                extractMemoryByType(memoryData, memoryType)
        );

        return kotlinEngine.render(template, data).trim();
    }

    private List<String> resolveGenreNamesSync(List<UUID> genreIds, AiAgent agent) {
        return genreIds.stream()
                .map(genreId -> refService.getById(genreId)
                        .map(genre -> genre.getLocalizedName().get(agent.getPreferredLang()))
                        .await().indefinitely())
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> extractMemoryByType(JsonObject memoryData, String memoryType) {
        JsonArray memoryArray = memoryData.getJsonArray(memoryType);
        List<Map<String, Object>> result = new ArrayList<>();
        if (memoryArray != null) {
            for (int i = 0; i < memoryArray.size(); i++) {
                result.add(memoryArray.getJsonObject(i).getMap());
            }
        }
        return result;
    }

}
