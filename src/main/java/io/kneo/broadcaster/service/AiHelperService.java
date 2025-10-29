package io.kneo.broadcaster.service;

import io.kneo.broadcaster.ai.DraftFactory;
import io.kneo.broadcaster.dto.ai.AiLiveAgentDTO;
import io.kneo.broadcaster.dto.ai.PromptDTO;
import io.kneo.broadcaster.dto.aihelper.BrandInfoDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.mcp.LiveContainerMcpDTO;
import io.kneo.broadcaster.dto.mcp.LiveRadioStationMcpDTO;
import io.kneo.broadcaster.dto.mcp.SongPromptMcpDTO;
import io.kneo.broadcaster.dto.mcp.TtsMcpDTO;
import io.kneo.broadcaster.mcp.SoundFragmentMCPTools;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.ScriptScene;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.Prompt;
import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.radiostation.AiOverriding;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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
    private final SoundFragmentMCPTools soundFragmentMCPTools;
    private final DraftFactory draftFactory;
    private static final List<RadioStationStatus> ACTIVE_STATUSES = List.of(
            RadioStationStatus.ON_LINE,
            RadioStationStatus.WARMING_UP,
            RadioStationStatus.WAITING_FOR_CURATOR,
            RadioStationStatus.IDLE
    );

    @Inject
    public AiHelperService(
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService,
            ScriptService scriptService,
            PromptService promptService,
            SongSupplier songSupplier,
            SoundFragmentMCPTools soundFragmentMCPTools,
            DraftFactory draftFactory
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.promptService = promptService;
        this.songSupplier = songSupplier;
        this.soundFragmentMCPTools = soundFragmentMCPTools;
        this.draftFactory = draftFactory;
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
        liveRadioStation.setRadioStationStatus(
                station.getStreamManager().getPlaylistManager().getPrioritizedQueue().size() > 2
                        ? RadioStationStatus.QUEUE_SATURATED
                        : station.getStatus()
        );

        return aiAgentService.getById(station.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .flatMap(agent -> {
                    liveRadioStation.setName(station.getLocalizedName().get(agent.getPreferredLang()));
                    liveRadioStation.setDjName(agent.getName());

                    return fetchPrompt(station).flatMap(prompts -> {
                        liveRadioStation.setPrompts(prompts);

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

    private Uni<List<SongPromptMcpDTO>> fetchPrompt(RadioStation station) {
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

                    LocalDateTime now = LocalDateTime.now();
                    LocalTime currentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    List<UUID> allPromptIds = new ArrayList<>();
                    for (BrandScript brandScript : scripts) {
                        List<ScriptScene> scenes = brandScript.getScript().getScenes();
                        for (ScriptScene scene : scenes) {
                            if (isSceneActive(scene, scenes, currentTime, currentDayOfWeek)) {
                                allPromptIds.addAll(scene.getPrompts());
                            }
                        }
                    }

                    if (allPromptIds.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }

                    List<Uni<Prompt>> promptUnis = allPromptIds.stream()
                            .map(id -> promptService.getById(id, SuperUser.build()))
                            .collect(Collectors.toList());

                    return Uni.join().all(promptUnis).andFailFast()
                            .flatMap(prompts -> {
                                LanguageCode djLanguage = agent.getPreferredLang();
                                List<Prompt> basicIntroPrompts = prompts.stream()
                                        .filter(p -> p.getPromptType() == PromptType.BASIC_INTRO)
                                        .filter(p -> p.getLanguageCode() == djLanguage)
                                        .toList();

                                if (basicIntroPrompts.isEmpty()) {
                                    return Uni.createFrom().item(() -> null);
                                }

                                Prompt selectedPrompt = basicIntroPrompts.get(new Random().nextInt(basicIntroPrompts.size()));
                                int songCount = soundFragmentMCPTools.decideFragmentCount();

                                return songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, songCount)
                                        .flatMap(songs -> {
                                            List<Uni<SongPromptMcpDTO>> songPromptUnis = songs.stream()
                                                    .map(song -> draftFactory.createDraft(
                                                            selectedPrompt.getPromptType(),
                                                            song,
                                                            agent,
                                                            station
                                                    ).map(draft -> new SongPromptMcpDTO(
                                                            song.getId(),
                                                            draft,
                                                            selectedPrompt.getPrompt(),
                                                            selectedPrompt.getPromptType(),
                                                            agent.getLlmType(),
                                                            agent.getSearchEngineType()
                                                    )))
                                                    .collect(Collectors.toList());

                                            return Uni.join().all(songPromptUnis).andFailFast();
                                        });
                            });
                });
    }



    @Deprecated
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
                            .filter(PromptDTO::isEnabled)
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

    private boolean isSceneActive(ScriptScene scene, List<ScriptScene> allScenes, LocalTime currentTime, int currentDayOfWeek) {
        if (!scene.getWeekdays().isEmpty() && !scene.getWeekdays().contains(currentDayOfWeek)) {
            return false;
        }

        if (scene.getStartTime() == null) {
            return true;
        }

        LocalTime sceneStart = scene.getStartTime();
        LocalTime nextSceneStart = findNextSceneStartTime(scene, allScenes);

        assert nextSceneStart != null;
        if (nextSceneStart.isAfter(sceneStart)) {
            return !currentTime.isBefore(sceneStart) && currentTime.isBefore(nextSceneStart);
        } else {
            return !currentTime.isBefore(sceneStart) || currentTime.isBefore(nextSceneStart);
        }
    }

    private LocalTime findNextSceneStartTime(ScriptScene currentScene, List<ScriptScene> scenesWithTime) {
        LocalTime currentStart = currentScene.getStartTime();
        
        List<LocalTime> sortedTimes = scenesWithTime.stream()
                .map(ScriptScene::getStartTime)
                .sorted()
                .distinct()
                .toList();

        for (LocalTime time : sortedTimes) {
            if (time.isAfter(currentStart)) {
                return time;
            }
        }

        return sortedTimes.isEmpty() ? null : sortedTimes.get(0);
    }
}