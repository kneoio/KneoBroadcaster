package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.agent.WeatherApiClient;
import io.kneo.broadcaster.agent.WorldNewsApiClient;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.Profile;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.Voice;
import io.kneo.broadcaster.model.radiostation.AiOverriding;
import io.kneo.broadcaster.model.radiostation.ProfileOverriding;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.DraftService;
import io.kneo.broadcaster.service.ProfileService;
import io.kneo.broadcaster.service.RefService;
import io.kneo.broadcaster.template.GroovyTemplateEngine;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool;

@ApplicationScoped
public class DraftFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftFactory.class);

    private final RefService refService;
    private final ProfileService profileService;
    private final DraftService draftService;
    private final AiAgentService aiAgentService;
    private final WeatherApiClient weatherApiClient;
    private final WorldNewsApiClient worldNewsApiClient;
    private final Random random = new Random();
    private final GroovyTemplateEngine groovyEngine;

    @Inject
    public DraftFactory(RefService refService, ProfileService profileService, DraftService draftService,
                       AiAgentService aiAgentService, WeatherApiClient weatherApiClient, 
                       WorldNewsApiClient worldNewsApiClient) {
        this.refService = refService;
        this.profileService = profileService;
        this.draftService = draftService;
        this.aiAgentService = aiAgentService;
        this.weatherApiClient = weatherApiClient;
        this.worldNewsApiClient = worldNewsApiClient;
        this.groovyEngine = new GroovyTemplateEngine();
    }

    public Uni<String> createDraft(
            SoundFragment song,
            AiAgent agent,
            RadioStation station,
            UUID draftId,
            LanguageCode selectedLanguage
    ) {
        Uni<AiAgent> copilotUni = agent.getCopilot() != null
                ? aiAgentService.getById(agent.getCopilot(), SuperUser.build(), selectedLanguage)
                : Uni.createFrom().nullItem();
        
        return Uni.combine().all()
                .unis(
                        getDraftTemplate(draftId, station.getSlugName(), selectedLanguage),
                        profileService.getById(station.getProfileId()),
                        resolveGenreNames(song, selectedLanguage),
                        copilotUni
                )
                .asTuple()
                .emitOn(getDefaultWorkerPool())
                .map(tuple -> {
                    Draft template = tuple.getItem1();
                    Profile profile = tuple.getItem2();
                    List<String> genres = tuple.getItem3();
                    AiAgent copilot = tuple.getItem4();

                    if (template != null) {
                        return buildFromTemplate(
                                template.getContent(),
                                song,
                                agent,
                                copilot,
                                station,
                                profile,
                                genres,
                                selectedLanguage
                        );
                    } else {
                        String msg = String.format("No draft template found for language=%s. Fallbacks are disabled.", selectedLanguage);
                        LOGGER.error(msg);
                        throw new IllegalStateException(msg);
                    }
                });
    }

    public Uni<String> createDraftFromCode(
            String code,
            SoundFragment song,
            AiAgent agent,
            RadioStation station,
            LanguageCode selectedLanguage
    ) {
        Uni<AiAgent> copilotUni = agent.getCopilot() != null
                ? aiAgentService.getById(agent.getCopilot(), SuperUser.build(), selectedLanguage)
                : Uni.createFrom().nullItem();
        
        return Uni.combine().all()
                .unis(
                        profileService.getById(station.getProfileId()),
                        resolveGenreNames(song, selectedLanguage),
                        copilotUni
                )
                .asTuple()
                .emitOn(getDefaultWorkerPool())
                .map(tuple -> {
                    Profile profile = tuple.getItem1();
                    List<String> genres = tuple.getItem2();
                    AiAgent copilot = tuple.getItem3();

                    return buildFromTemplate(
                            code,
                            song,
                            agent,
                            copilot,
                            station,
                            profile,
                            genres,
                            selectedLanguage
                    );
                });
    }

    private Uni<Draft> getDraftTemplate(UUID id, String stationSlug, LanguageCode language) {
        if (id == null) {
            String errorMsg = String.format(
                "Prompt configuration error: draftId is null for station='%s', language='%s'. Check prompt configuration - all prompts must have an associated draft template.",
                stationSlug, language
            );
            LOGGER.error(errorMsg);
            return Uni.createFrom().failure(new IllegalStateException(errorMsg));
        }
        return draftService.getById(id, SuperUser.build())
                .onFailure().transform(t -> {
                    String errorMsg = String.format(
                        "Draft template not found: draftId='%s', station='%s', language='%s'. Error: %s",
                        id, stationSlug, language, t.getMessage()
                    );
                    LOGGER.error(errorMsg, t);
                    return new IllegalStateException(errorMsg, t);
                });
    }

    private String buildFromTemplate(
            String template,
            SoundFragment song,
            AiAgent agent,
            AiAgent copilot,
            RadioStation station,
            Profile profile,
            List<String> genres,
            LanguageCode selectedLanguage
    ) {
        String countryIso = station.getCountry().getIsoCode();
        Map<String, Object> data = new HashMap<>();
        data.put("songTitle", song.getTitle());
        data.put("songArtist", song.getArtist());
        data.put("songDescription", song.getDescription());
        data.put("songGenres", genres);
        data.put("coPilotName", copilot.getName());
        data.put("coPilotVoiceId", copilot.getPrimaryVoice().stream().findAny().orElse(new Voice("Kuon","B8gJV1IhpuegLxdpXFOE")).getId());
        String brand = station.getLocalizedName().get(selectedLanguage);
        if (brand == null) {
            brand = station.getLocalizedName().values().iterator().next();
        }
        AiOverriding overriddenAiDj = station.getAiOverriding();
        if (overriddenAiDj != null){
            data.put("djName", overriddenAiDj.getName());
            data.put("djVoiceId", overriddenAiDj.getPrimaryVoice());
        } else {
            data.put("djName", agent.getName());
            data.put("djVoiceId", agent.getPrimaryVoice().stream().findAny().orElseThrow().getId());
        }
        ProfileOverriding overriddenProfile = station.getProfileOverriding();
        if (overriddenProfile != null){
            data.put("profileName", overriddenProfile.getName());
            data.put("profileDescription", overriddenProfile.getDescription());
        } else {
            data.put("profileName", profile.getName());
            data.put("profileDescription", profile.getDescription());
        }
        data.put("stationBrand", brand);
        data.put("country", station.getCountry());
        data.put("language", selectedLanguage);
        data.put("random", random);
        data.put("weather", new WeatherHelper(weatherApiClient, countryIso));
        data.put("news", new NewsHelper(worldNewsApiClient, countryIso, selectedLanguage.name()));

        return groovyEngine.render(template, data).trim();
    }

    private Uni<List<String>> resolveGenreNames(SoundFragment song, LanguageCode selectedLanguage) {
        List<UUID> genreIds = song.getGenres();
        if (genreIds == null || genreIds.isEmpty()) {
            LOGGER.warn("Song '{}' (ID: {}) has no genres assigned", song.getTitle(), song.getId());
            return Uni.createFrom().item(List.of());
        }
        
        List<Uni<String>> genreUnis = genreIds.stream()
                .map(genreId -> refService.getById(genreId)
                        .map(genre -> genre.getLocalizedName().get(selectedLanguage)))
                .collect(Collectors.toList());
        
        return Uni.join().all(genreUnis).andFailFast();
    }

}
