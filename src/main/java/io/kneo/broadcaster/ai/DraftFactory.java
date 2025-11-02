package io.kneo.broadcaster.ai;

import io.kneo.broadcaster.dto.memory.MemoryResult;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.Profile;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.cnst.DraftType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.DraftService;
import io.kneo.broadcaster.service.ProfileService;
import io.kneo.broadcaster.service.RefService;
import io.kneo.broadcaster.template.GroovyTemplateEngine;
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

@ApplicationScoped
public class DraftFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftFactory.class);

    private final RefService refService;
    private final ProfileService profileService;
    private final DraftService draftService;
    private final Random random = new Random();
    private final GroovyTemplateEngine groovyEngine;

    @Inject
    public DraftFactory(RefService refService, ProfileService profileService, DraftService draftService) {
        this.refService = refService;
        this.profileService = profileService;
        this.draftService = draftService;
        this.groovyEngine = new GroovyTemplateEngine();
    }

    public Uni<String> createDraft(
            DraftType draftType,
            SoundFragment song,
            AiAgent agent,
            RadioStation station,
            MemoryResult memoryData
    ) {
        
        return Uni.combine().all()
                .unis(
                        getDraftTemplate(draftType, agent.getPreferredLang()),
                        profileService.getById(station.getProfileId()),
                        resolveGenreNames(song.getGenres(), agent)
                )
                .asTuple()
                .map(tuple -> {
                    Draft template = tuple.getItem1();
                    Profile profile = tuple.getItem2();
                    List<String> genres = tuple.getItem3();

                    if (template != null) {
                        return buildFromTemplate(
                                template.getContent(),
                                song,
                                agent,
                                station,
                                memoryData,
                                profile,
                                genres
                        );
                    } else {
                        String msg = String.format("No draft template found for type=%s, language=%s. Fallbacks are disabled.",
                                draftType, agent.getPreferredLang());
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
            MemoryResult memoryData
    ) {
        return Uni.combine().all()
                .unis(
                        profileService.getById(station.getProfileId()),
                        resolveGenreNames(song.getGenres(), agent)
                )
                .asTuple()
                .map(tuple -> {
                    Profile profile = tuple.getItem1();
                    List<String> genres = tuple.getItem2();

                    return buildFromTemplate(
                            code,
                            song,
                            agent,
                            station,
                            memoryData,
                            profile,
                            genres
                    );
                });
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
            MemoryResult memoryData,
            Profile profile,
            List<String> genres
    ) {
        var history = memoryData.getConversationHistory();
        var messages = memoryData.getMessages();
        var events = memoryData.getEvents();
        
        Map<String, Object> data = new HashMap<>();
        data.put("songTitle", song.getTitle());
        data.put("songArtist", song.getArtist());
        data.put("songDescription", song.getDescription());
        data.put("songGenres", genres);
        data.put("agentName", agent.getName());
        data.put("stationBrand", station.getLocalizedName().get(agent.getPreferredLang()));
        data.put("profileName", profile.getName());
        data.put("profileDescription", profile.getDescription());
        data.put("history", history);
        data.put("messages", messages);
        data.put("events", events);
        data.put("random", random); //we will use Java random

        return groovyEngine.render(template, data).trim();
    }

    private Uni<List<String>> resolveGenreNames(List<UUID> genreIds, AiAgent agent) {
        List<Uni<String>> genreUnis = genreIds.stream()
                .map(genreId -> refService.getById(genreId)
                        .map(genre -> genre.getLocalizedName().get(agent.getPreferredLang())))
                .collect(Collectors.toList());
        
        return Uni.join().all(genreUnis).andFailFast();
    }


}
