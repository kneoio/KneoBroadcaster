package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ai.AiLiveAgentDTO;
import io.kneo.broadcaster.dto.ai.PromptDTO;
import io.kneo.broadcaster.dto.aihelper.BrandInfoDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.dto.mcp.LiveContainerMcpDTO;
import io.kneo.broadcaster.dto.mcp.LivePromptMcpDTO;
import io.kneo.broadcaster.dto.mcp.LiveRadioStationMcpDTO;
import io.kneo.broadcaster.dto.mcp.TtsMcpDTO;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Profile;
import io.kneo.broadcaster.model.ScriptScene;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.DraftBuilder;
import io.kneo.broadcaster.model.ai.Prompt;
import io.kneo.broadcaster.model.cnst.AiAgentMode;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.radiostation.AiOverriding;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {

    private final RadioStationPool radioStationPool;
    private final AiAgentService aiAgentService;
    private final ScriptService scriptService;
    private final PromptService promptService;
    private final SongSupplier songSupplier;
    private final MemoryService memoryService;
    private final ProfileService profileService;
    private static final List<RadioStationStatus> ACTIVE_STATUSES = List.of(
            RadioStationStatus.ON_LINE,
            RadioStationStatus.WARMING_UP,
            RadioStationStatus.QUEUE_SATURATED,
            RadioStationStatus.WAITING_FOR_CURATOR
    );

    @Inject
    public AiHelperService(
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService,
            ScriptService scriptService,
            PromptService promptService,
            SongSupplier songSupplier,
            MemoryService memoryService,
            ProfileService profileService
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.promptService = promptService;
        this.songSupplier = songSupplier;
        this.memoryService = memoryService;
        this.profileService = profileService;
    }

    public Uni<LiveContainerMcpDTO> getOnline() {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> ACTIVE_STATUSES.contains(station.getStatus()))
                        .filter(station -> !station.getScheduler().isEnabled() || station.isAiControlAllowed())
                        .collect(Collectors.toList())
        ).flatMap(stations -> {
            LiveContainerMcpDTO container = new LiveContainerMcpDTO();
            if (stations.isEmpty()) {
                container.setRadioStations(List.of());
                return Uni.createFrom().item(container);
            }
            List<Uni<LiveRadioStationMcpDTO>> stationUnis = stations.stream()
                    .map(this::buildLiveRadioStation)
                    .collect(Collectors.toList());

            return Uni.join().all(stationUnis).andFailFast()
                    .map(liveStations -> {

                        container.setRadioStations(liveStations);
                        return container;
                    });
        });
    }

    private Uni<LiveRadioStationMcpDTO> buildLiveRadioStation(RadioStation station) {
        LiveRadioStationMcpDTO liveRadioStation = new LiveRadioStationMcpDTO();
        liveRadioStation.setName(station.getSlugName());
        liveRadioStation.setDjName(station.getSlugName());
        liveRadioStation.setRadioStationStatus(
                station.getStreamManager().getPlaylistManager().getPrioritizedQueue().size() > 2
                        ? RadioStationStatus.QUEUE_SATURATED
                        : station.getStatus()
        );

        UUID agentId = station.getAiAgentId();
        
        return aiAgentService.getById(agentId, SuperUser.build(), LanguageCode.en)
                .flatMap(agent -> {
                    Uni<LivePromptMcpDTO> promptUni;
                    if (station.getAiAgentMode() == AiAgentMode.SCRIPT_FOLLOWING) {
                        promptUni = fetchPromptForStation(station);
                    } else {
                        promptUni = fetchPromptFromAgent(station);
                    }

                    return promptUni.flatMap(prompt -> {
                        liveRadioStation.setPrompt(prompt);

                        String preferredVoice = agent.getPreferredVoice().get(0).getId();
                        UUID copilotId = agent.getCopilot();

                        return aiAgentService.getDTO(copilotId, SuperUser.build(), LanguageCode.en)
                                .map(copilot -> {
                                    String secondaryVoice = copilot.getPreferredVoice().get(0).getId();
                                    String secondaryVoiceName = copilot.getName();
                                    liveRadioStation.setTts(new TtsMcpDTO(
                                            preferredVoice,
                                            secondaryVoice,
                                            secondaryVoiceName
                                    ));
                                    return liveRadioStation;
                                });
                    });
                });
    }

    private Uni<LivePromptMcpDTO> fetchPromptForStation(RadioStation station) {
        UUID agentId = station.getAiAgentId();
        if (agentId == null) {
            return Uni.createFrom().item(() -> null);
        }

        return Uni.combine().all()
                .unis(
                        scriptService.getAllScriptsForBrandWithScenes(station.getId(), SuperUser.build()),
                        aiAgentService.getById(agentId, SuperUser.build(), LanguageCode.en)
                )
                .asTuple()
                .flatMap(tuple -> {
                    List<BrandScript> scripts = tuple.getItem1();
                    AiAgent agent = tuple.getItem2();

                    if (scripts.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }

                    List<UUID> allPromptIds = new ArrayList<>();
                    for (BrandScript brandScript : scripts) {
                        for (ScriptScene scene : brandScript.getScript().getScenes()) {
                            if (scene.getPrompts() != null) {
                                allPromptIds.addAll(scene.getPrompts());
                            }
                        }
                    }

                    if (allPromptIds.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }

                    UUID promptId = allPromptIds.get(new Random().nextInt(allPromptIds.size()));

                    return Uni.combine().all()
                            .unis(
                                    songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, 1),
                                    memoryService.getByType(station.getSlugName(), "CONVERSATION_HISTORY"),
                                    profileService.getById(station.getProfileId())
                            )
                            .asTuple()
                            .flatMap(innerTuple -> {
                                SoundFragment song = innerTuple.getItem1().get(0);
                                JsonObject memoryData = innerTuple.getItem2();
                                JsonArray historyArray = memoryData.getJsonArray("history");
                                
                                List<Map<String, Object>> history = new ArrayList<>();
                                for (int i = 0; i < historyArray.size(); i++) {
                                    history.add(historyArray.getJsonObject(i).getMap());
                                }
                                
                                Profile profile = innerTuple.getItem3();
                                Map<String, Object> context = Map.of(
                                        "name", profile.getName(),
                                        "description", profile.getDescription()
                                );
                                
                                DraftBuilder draftBuilder = new DraftBuilder(
                                        song.getTitle(),
                                        song.getArtist(),
                                        song.getGenres().stream().map(UUID::toString).toList(),
                                        song.getDescription(),
                                        agent.getName(),
                                        station.getLocalizedName().get(agent.getPreferredLang()),
                                        history,
                                        List.of(context)
                                );

                                return promptService.getById(promptId, SuperUser.build())
                                        .map(prompt -> new LivePromptMcpDTO(
                                                draftBuilder.build(),
                                                prompt.getPrompt(),
                                                prompt.getPromptType(),
                                                agent.getLlmType(),
                                                agent.getSearchEngineType()
                                        ));
                            });
                });
    }

    private Uni<LivePromptMcpDTO> fetchPromptFromAgent(RadioStation station) {
        UUID agentId = station.getAiAgentId();
        if (agentId == null) {
            return Uni.createFrom().item(() -> null);
        }

        return aiAgentService.getById(agentId, SuperUser.build(), LanguageCode.en)
                .flatMap(agent -> {
                    List<Prompt> prompts = agent.getPrompts();
                    if (prompts.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }

                    List<Prompt> enabledPrompts = prompts.stream()
                            .filter(Prompt::isEnabled)
                            .toList();

                    if (enabledPrompts.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }

                    Prompt prompt = enabledPrompts.get(new Random().nextInt(enabledPrompts.size()));

                    return Uni.combine().all()
                            .unis(
                                    songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, 1),
                                    memoryService.getByType(station.getSlugName(), "CONVERSATION_HISTORY"),
                                    profileService.getById(station.getProfileId())
                            )
                            .asTuple()
                            .map(tuple -> {
                                SoundFragment song = tuple.getItem1().get(0);
                                JsonObject memoryData = tuple.getItem2();
                                JsonArray historyArray = memoryData.getJsonArray("history");

                                List<Map<String, Object>> history = new ArrayList<>();
                                for (int i = 0; i < historyArray.size(); i++) {
                                    history.add(historyArray.getJsonObject(i).getMap());
                                }

                                Profile profile = tuple.getItem3();
                                Map<String, Object> context = Map.of(
                                        "name", profile.getName(),
                                        "description", profile.getDescription()
                                );

                                DraftBuilder draftBuilder = new DraftBuilder(
                                        song.getTitle(),
                                        song.getArtist(),
                                        song.getGenres().stream().map(UUID::toString).toList(),
                                        song.getDescription(),
                                        agent.getName(),
                                        station.getLocalizedName().get(agent.getPreferredLang()),
                                        history,
                                        List.of(context)
                                );

                                return new LivePromptMcpDTO(
                                        draftBuilder.build(),
                                        prompt.getPrompt(),
                                        prompt.getPromptType(),
                                        agent.getLlmType(),
                                        agent.getSearchEngineType()
                                );
                            });
                });
    }


    public Uni<List<BrandInfoDTO>> getByStatus(List<RadioStationStatus> statuses) {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> statuses.contains(station.getStatus()))
                        .filter(station -> !station.getScheduler().isEnabled() || station.isAiControlAllowed())
                        .collect(Collectors.toList())
        ).chain(stations -> {
            if (stations.isEmpty()) {
                return Uni.createFrom().item(List.of());
            }

            List<Uni<BrandInfoDTO>> tasks = stations.stream().map(station -> {
                BrandInfoDTO brand = new BrandInfoDTO();
                brand.setRadioStationName(station.getSlugName());

                if (station.getStreamManager().getPlaylistManager().getPrioritizedQueue().size() > 2) {
                    brand.setRadioStationStatus(RadioStationStatus.QUEUE_SATURATED);
                } else {
                    brand.setRadioStationStatus(station.getStatus());
                }

                UUID agentId = station.getAiAgentId();
                if (agentId == null) {
                    return Uni.createFrom().item(brand);
                }

                return aiAgentService.getDTO(agentId, SuperUser.build(), LanguageCode.en).flatMap(agent -> {
                    AiLiveAgentDTO dto = new AiLiveAgentDTO();
                    dto.setName(agent.getName());
                    dto.setLlmType(io.kneo.broadcaster.model.ai.LlmType.valueOf(agent.getLlmType()));
                    dto.setPreferredLang(io.kneo.core.localization.LanguageCode.valueOf(agent.getPreferredLang()));

                    List<PromptDTO> prompts = agent.getPrompts();
                    List<String> msgPrompts = agent.getMessagePrompts();
                    List<String> podcastPrompts = agent.getMiniPodcastPrompts();

                    if (prompts.isEmpty()) {
                        return Uni.createFrom().item(brand);
                    }

                    List<PromptDTO> enabledPrompts = prompts.stream()
                            .filter(p -> p.isEnabled())
                            .toList();
                    String randomPrompt = enabledPrompts.get(new Random().nextInt(enabledPrompts.size())).getPrompt();


                    String msgPrompt;
                    if (msgPrompts != null && !msgPrompts.isEmpty()) {
                        msgPrompt = msgPrompts.get(new Random().nextInt(msgPrompts.size()));
                    } else {
                        msgPrompt = randomPrompt;
                    }

                    String podcastPrompt;
                    if (podcastPrompts != null && !podcastPrompts.isEmpty()) {
                        podcastPrompt = podcastPrompts.get(new Random().nextInt(podcastPrompts.size()));
                    } else {
                        podcastPrompt = randomPrompt;
                    }

                    dto.setMessagePrompt(msgPrompt);
                    dto.setMiniPodcastPrompt(podcastPrompt);
                    dto.setPodcastMode(agent.getPodcastMode());

                    AiOverriding override = station.getAiOverriding();
                    if (override != null) {
                        if (!override.getName().isEmpty()) {
                            dto.setName(override.getName());
                        }
                        dto.setPreferredVoice(override.getPreferredVoice());
                        dto.setTalkativity(override.getTalkativity());
                        dto.setPrompt(String.format("%s\n------\n%s", randomPrompt, override.getPrompt()));
                        brand.setAgent(dto);
                        return Uni.createFrom().item(brand);
                    } else {
                        dto.setPreferredVoice(agent.getPreferredVoice().get(0).getId());
                        dto.setTalkativity(agent.getTalkativity());
                        dto.setPrompt(randomPrompt);

                        UUID copilotId = agent.getCopilot();
                        if (copilotId != null) {
                            return aiAgentService.getById(copilotId, SuperUser.build(), LanguageCode.en)
                                    .map(copilot -> {
                                        dto.setSecondaryVoice(copilot.getPreferredVoice().get(0).getId());
                                        dto.setSecondaryVoiceName(copilot.getName());
                                        brand.setAgent(dto);
                                        return brand;
                                    });
                        } else {
                            brand.setAgent(dto);
                            return Uni.createFrom().item(brand);
                        }
                    }
                });
            }).collect(Collectors.toList());

            return Uni.join().all(tasks).andFailFast();
        });
    }
}