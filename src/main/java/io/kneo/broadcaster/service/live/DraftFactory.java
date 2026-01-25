package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.agent.PerplexityApiClient;
import io.kneo.broadcaster.agent.WeatherApiClient;
import io.kneo.broadcaster.agent.WorldNewsApiClient;
import io.kneo.broadcaster.dto.BrandListenerDTO;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.Profile;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.RadioStream;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.DraftService;
import io.kneo.broadcaster.service.ListenerService;
import io.kneo.broadcaster.service.ProfileService;
import io.kneo.broadcaster.template.GroovyTemplateEngine;
import io.kneo.broadcaster.util.TimeContextUtil;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.officeframe.service.GenreService;
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

    private final GenreService genreService;
    private final ProfileService profileService;
    private final DraftService draftService;
    private final AiAgentService aiAgentService;
    private final WeatherApiClient weatherApiClient;
    private final WorldNewsApiClient worldNewsApiClient;
    private final PerplexityApiClient perplexityApiClient;
    private final ListenerService listenerService;
    private final Random random = new Random();
    private final GroovyTemplateEngine groovyEngine;

    @Inject
    public DraftFactory(GenreService genreService, ProfileService profileService, DraftService draftService,
                        AiAgentService aiAgentService, WeatherApiClient weatherApiClient,
                        WorldNewsApiClient worldNewsApiClient, PerplexityApiClient perplexityApiClient,
                        ListenerService listenerService) {
        this.genreService = genreService;
        this.profileService = profileService;
        this.draftService = draftService;
        this.aiAgentService = aiAgentService;
        this.weatherApiClient = weatherApiClient;
        this.worldNewsApiClient = worldNewsApiClient;
        this.perplexityApiClient = perplexityApiClient;
        this.listenerService = listenerService;
        this.groovyEngine = new GroovyTemplateEngine();
    }

    public Uni<String> createDraft(
            SoundFragment song,
            AiAgent agent,
            IStream stream,
            UUID draftId,
            LanguageTag selectedLanguage,  //always EN
            Map<String, Object> userVariables
    ) {
        Uni<AiAgent> copilotUni = agent.getCopilot() != null
                ? aiAgentService.getById(agent.getCopilot(), SuperUser.build(), selectedLanguage.toLanguageCode())
                : Uni.createFrom().nullItem();
        
        Uni<List<String>> genresUni = song != null
                ? resolveGenreNames(song, selectedLanguage.toLanguageCode())
                : Uni.createFrom().item(List.of());
        
        return Uni.combine().all()
                .unis(
                        getDraftTemplate(draftId, stream.getSlugName()),  //the drafts always un ENG
                        profileService.getById(stream.getProfileId()),
                        genresUni,
                        copilotUni,
                        listenerService.getBrandListeners(stream.getSlugName(), 500, 0, SuperUser.build(), null)
                )
                .asTuple()
                .emitOn(getDefaultWorkerPool())
                .map(tuple -> {
                    Draft template = tuple.getItem1();
                    Profile profile = tuple.getItem2();
                    List<String> genres = tuple.getItem3();
                    AiAgent copilot = tuple.getItem4();
                    List<io.kneo.broadcaster.dto.BrandListenerDTO> listeners = tuple.getItem5();

                    if (template != null) {
                        return buildFromTemplate(
                                template.getContent(),
                                song,
                                agent,
                                copilot,
                                stream,
                                profile,
                                genres,
                                listeners,
                                selectedLanguage,
                                userVariables
                        );
                    } else {
                        String msg = "No draft template found. Fallbacks are disabled.";
                        LOGGER.error(msg);
                        throw new IllegalStateException(msg);
                    }
                });
    }

    public Uni<String> createDraftFromCode(
            String code,
            SoundFragment song,
            AiAgent agent,
            Brand station,
            LanguageTag selectedLanguage,
            Map<String, Object> userVariables
    ) {
        RadioStream radioStream = new RadioStream();
        radioStream.setMasterBrand(station);
        radioStream.setCountry(station.getCountry());
        radioStream.setLocalizedName(station.getLocalizedName());
        radioStream.setSlugName(station.getSlugName());
        radioStream.setTimeZone(station.getTimeZone());
        Uni<AiAgent> copilotUni = agent.getCopilot() != null
                ? aiAgentService.getById(agent.getCopilot(), SuperUser.build(), selectedLanguage.toLanguageCode())
                : Uni.createFrom().nullItem();
        
        return Uni.combine().all()
                .unis(
                        profileService.getById(station.getProfileId()),
                        resolveGenreNames(song, selectedLanguage.toLanguageCode()),
                        copilotUni,
                        listenerService.getBrandListeners(station.getSlugName(), 500, 0, SuperUser.build(), null)
                )
                .asTuple()
                .emitOn(getDefaultWorkerPool())
                .map(tuple -> {
                    Profile profile = tuple.getItem1();
                    List<String> genres = tuple.getItem2();
                    AiAgent copilot = tuple.getItem3();
                    List<io.kneo.broadcaster.dto.BrandListenerDTO> listeners = tuple.getItem4();

                    return buildFromTemplate(
                            code,
                            song,
                            agent,
                            copilot,
                            radioStream,
                            profile,
                            genres,
                            listeners,
                            selectedLanguage,
                            userVariables
                    );
                });
    }

    private Uni<Draft> getDraftTemplate(UUID id, String stationSlug) {
        if (id == null) {
            String errorMsg = String.format(
                "Prompt configuration error: draftId is null for station='%s'. Check prompt configuration - all prompts must have an associated draft template.",
                stationSlug
            );
            LOGGER.error(errorMsg);
            return Uni.createFrom().failure(new IllegalStateException(errorMsg));
        }
        return draftService.getById(id, SuperUser.build())
                .onFailure().transform(t -> {
                    String errorMsg = String.format(
                        "Draft template not found: draftId='%s', station='%s'. Error: %s",
                        id, stationSlug, t.getMessage()
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
            IStream stream,
            Profile profile,
            List<String> genres,
            List<BrandListenerDTO> listeners,
            LanguageTag selectedLanguage,
            Map<String, Object> userVariables
    ) {
        String countryIso = stream.getCountry().getIsoCode();
        Map<String, Object> data = new HashMap<>();
        
        if (userVariables != null && !userVariables.isEmpty()) {
            data.putAll(userVariables);
        }
        
        data.put("coPilotName", copilot.getName());
        data.put("coPilotVoiceId", copilot.getTtsSetting().getDj().getId());
        data.put("listeners", listeners);
        String brand = stream.getLocalizedName().get(selectedLanguage.toLanguageCode());
        if (brand == null) {
            brand = stream.getLocalizedName().values().iterator().next();
        }
        AiOverriding overriddenAiDj = stream.getAiOverriding();
        if (overriddenAiDj != null){
            data.put("djName", overriddenAiDj.getName());
            data.put("djVoiceId", overriddenAiDj.getPrimaryVoice());
        } else {
            data.put("djName", agent.getName());
            data.put("djVoiceId", agent.getTtsSetting().getDj().getId());
        }
        ProfileOverriding overriddenProfile = stream.getProfileOverriding();
        if (overriddenProfile != null){
            data.put("profileName", overriddenProfile.getName());
            data.put("profileDescription", overriddenProfile.getDescription());
        } else {
            data.put("profileName", profile.getName());
            data.put("profileDescription", profile.getDescription());
        }
        data.put("stationBrand", brand);
        data.put("slugName", stream.getSlugName());
        data.put("country", stream.getCountry());
        data.put("language", selectedLanguage);
        data.put("random", random);
        data.put("perplexity", new PerplexitySearchHelper(perplexityApiClient));
        data.put("weather", new WeatherHelper(weatherApiClient, countryIso));
        data.put("news", new NewsHelper(worldNewsApiClient, countryIso, selectedLanguage.name()));
        data.put("timeContext", TimeContextUtil.getCurrentMomentDetailed(stream.getTimeZone()));
        
        if (song != null) {
            data.put("songTitle", song.getTitle());
            data.put("songArtist", song.getArtist());
            data.put("songDescription", song.getDescription());
            data.put("songGenres", genres);
        } else {
            data.put("songTitle", "");
            data.put("songArtist", "");
            data.put("songDescription", "");
            data.put("songGenres", List.of());
        }

        return groovyEngine.render(template, data).trim();
    }

    private Uni<List<String>> resolveGenreNames(SoundFragment song, LanguageCode selectedLanguage) {
        List<UUID> genreIds = song.getGenres();
        if (genreIds == null || genreIds.isEmpty()) {
            LOGGER.warn("Song '{}' (ID: {}) has no genres assigned", song.getTitle(), song.getId());
            return Uni.createFrom().item(List.of());
        }
        
        List<Uni<String>> genreUnis = genreIds.stream()
                .map(genreId -> genreService.getById(genreId)
                        .map(genre -> genre.getLocalizedName().get(selectedLanguage)))
                .collect(Collectors.toList());
        
        return Uni.join().all(genreUnis).andFailFast();
    }

}
