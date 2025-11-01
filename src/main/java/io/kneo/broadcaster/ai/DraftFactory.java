package io.kneo.broadcaster.ai;

import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.DraftService;
import io.kneo.broadcaster.template.KotlinTemplateEngine;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.ProfileService;
import io.kneo.broadcaster.service.RefService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@ApplicationScoped
public class DraftFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftFactory.class);

    private final RefService refService;
    private final MemoryService memoryService;
    private final ProfileService profileService;
    private final DraftService draftService;
    private final Random random = new Random();
    private final KotlinTemplateEngine kotlinEngine = new KotlinTemplateEngine();

    @Inject
    public DraftFactory(RefService refService, MemoryService memoryService, ProfileService profileService, DraftService draftService) {
        this.refService = refService;
        this.memoryService = memoryService;
        this.profileService = profileService;
        this.draftService = draftService;
    }

    public Uni<String> createDraft(
            PromptType promptType,
            SoundFragment song,
            AiAgent agent,
            RadioStation station
    ) {
        String draftType = mapPromptTypeToDraftType(promptType);
        
        return Uni.combine().all()
                .unis(
                        getDraftTemplate(draftType, agent.getPreferredLang()),
                        resolveGenreNames(song, agent),
                        resolveHistory(station),
                        resolveEnvironment(station)
                )
                .asTuple()
                .map(tuple -> {
                    Draft template = tuple.getItem1();
                    List<String> genreNames = tuple.getItem2();
                    List<Map<String, Object>> history = tuple.getItem3();
                    Map<String, Object> environment = tuple.getItem4();

                    if (template != null) {
                        return buildFromTemplate(
                                template.getContent(),
                                song,
                                agent,
                                station,
                                genreNames,
                                history,
                                environment
                        );
                    } else {
                        String msg = String.format("No draft template found for type=%s, language=%s. Fallbacks are disabled.",
                                draftType, agent.getPreferredLang());
                        LOGGER.error(msg);
                        throw new IllegalStateException(msg);
                    }
                });
    }

    private String mapPromptTypeToDraftType(PromptType promptType) {
        return switch (promptType) {
            case BASIC_INTRO -> "INTRO_DRAFT";
            case USER_MESSAGE -> "MESSAGE_DRAFT";
            case NEWS, WEATHER, EVENT -> "NEWS_INTRO_DRAFT";
        };
    }

    private Uni<Draft> getDraftTemplate(String draftType, io.kneo.core.localization.LanguageCode languageCode) {
        return draftService.getAll()
                .map(drafts -> drafts.stream()
                        .filter(d -> draftType.equals(d.getDraftType()) && 
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
            List<String> genreNames,
            List<Map<String, Object>> history,
            Map<String, Object> environment
    ) {
        boolean combinedIntro = random.nextDouble() < 0.5;
        boolean showDj = random.nextDouble() < 0.3;
        boolean showBrand = random.nextDouble() < 0.4;
        boolean showAtmosphere = random.nextDouble() < 0.7;

        String contextStr = environment.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));

        Map<String, Object> lastHistory = null;
        if (!history.isEmpty()) {
            lastHistory = history.get(history.size() - 1);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("title", song.getTitle());
        data.put("artist", song.getArtist());
        data.put("aiDjName", agent.getName());
        data.put("brand", station.getLocalizedName().get(agent.getPreferredLang()));
        data.put("songDescription", song.getDescription());
        data.put("genres", genreNames);
        data.put("history", lastHistory);
        data.put("context", contextStr);
        data.put("combinedIntro", combinedIntro);
        data.put("showDj", showDj);
        data.put("showBrand", showBrand);
        data.put("showAtmosphere", showAtmosphere);

        return kotlinEngine.render(template, data).trim();
    }

    private Uni<List<String>> resolveGenreNames(SoundFragment song, AiAgent agent) {
        return Uni.join().all(
                song.getGenres().stream()
                        .map(genreId -> refService.getById(genreId)
                                .map(genre -> genre.getLocalizedName().get(agent.getPreferredLang())))
                        .collect(Collectors.toList())
        ).andFailFast();
    }

    private Uni<List<Map<String, Object>>> resolveHistory(RadioStation station) {
        return memoryService.getByType(station.getSlugName(), MemoryType.CONVERSATION_HISTORY.name())
                .map(memoryData -> {
                    JsonArray historyArray = memoryData.getJsonArray("history");
                    List<Map<String, Object>> history = new ArrayList<>();
                    for (int i = 0; i < historyArray.size(); i++) {
                        history.add(historyArray.getJsonObject(i).getMap());
                    }
                    return history;
                });
    }

    private Uni<Map<String, Object>> resolveEnvironment(RadioStation station) {
        return profileService.getById(station.getProfileId())
                .map(profile -> Map.of(
                        "name", profile.getName(),
                        "description", profile.getDescription()
                ));
    }
}
