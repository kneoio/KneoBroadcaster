package io.kneo.broadcaster.ai;

import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.ProfileService;
import io.kneo.broadcaster.service.RefService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class DraftFactory {

    private final RefService refService;
    private final MemoryService memoryService;
    private final ProfileService profileService;

    @Inject
    public DraftFactory(RefService refService, MemoryService memoryService, ProfileService profileService) {
        this.refService = refService;
        this.memoryService = memoryService;
        this.profileService = profileService;
    }

    public Uni<String> createDraft(
            PromptType promptType,
            SoundFragment song,
            AiAgent agent,
            RadioStation station
    ) {
        return Uni.combine().all()
                .unis(
                        resolveGenreNames(song, agent),
                        resolveHistory(station),
                        resolveEnvironment(station)
                )
                .asTuple()
                .map(tuple -> {
                    List<String> genreNames = tuple.getItem1();
                    List<Map<String, Object>> history = tuple.getItem2();
                    Map<String, Object> environment = tuple.getItem3();

                    String title = song.getTitle();
                    String artist = song.getArtist();
                    String aiDjName = agent.getName();
                    String brand = station.getLocalizedName().get(agent.getPreferredLang());
                    List<Object> contextList = List.of(environment);

                    IDraft draft = switch (promptType) {
                        case BASIC_INTRO -> new IntroDraft(
                                title,
                                artist,
                                genreNames,
                                song.getDescription(),
                                aiDjName,
                                brand,
                                history,
                                contextList,
                                agent.getPreferredLang()
                        );
                        case USER_MESSAGE -> new MessageDraft(
                                title,
                                artist,
                                aiDjName,
                                brand,
                                contextList,
                                agent.getPreferredLang()
                        );
                        case NEWS, WEATHER, EVENT -> new NewsIntroDraft(
                                title,
                                artist,
                                aiDjName,
                                brand,
                                contextList,
                                agent.getPreferredLang()
                        );
                    };

                    return draft.build();
                });
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
