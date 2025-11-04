package io.kneo.broadcaster.service;

import io.kneo.broadcaster.ai.DraftFactory;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStats;
import io.kneo.broadcaster.dto.mcp.LiveContainerMcpDTO;
import io.kneo.broadcaster.dto.mcp.LiveRadioStationMcpDTO;
import io.kneo.broadcaster.dto.mcp.SongPromptMcpDTO;
import io.kneo.broadcaster.dto.mcp.TtsMcpDTO;
import io.kneo.broadcaster.dto.memory.MemoryResult;
import io.kneo.broadcaster.mcp.SoundFragmentMCPTools;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.ScriptScene;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.Prompt;
import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.broadcaster.model.cnst.DraftType;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger log = LoggerFactory.getLogger(AiHelperService.class);

    public record DjRequestInfo(LocalDateTime requestTime, String djName) {}

    private final Map<String, DjRequestInfo> aiDjStatsRequestTracker = new ConcurrentHashMap<>();
    private final Map<String, List<AiDjStats.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();

    private final RadioStationPool radioStationPool;
    private final AiAgentService aiAgentService;
    private final ScriptService scriptService;
    private final PromptService promptService;
    private final SongSupplier songSupplier;
    private final SoundFragmentMCPTools soundFragmentMCPTools;
    private final DraftFactory draftFactory;
    private final MemoryService memoryService;
    private static final List<RadioStationStatus> ACTIVE_STATUSES = List.of(
            RadioStationStatus.ON_LINE,
            RadioStationStatus.WARMING_UP,  //keep it for now while developing stage
            RadioStationStatus.QUEUE_SATURATED
    );

    @Inject
    public AiHelperService(
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService,
            ScriptService scriptService,
            PromptService promptService,
            SongSupplier songSupplier,
            SoundFragmentMCPTools soundFragmentMCPTools,
            DraftFactory draftFactory,
            MemoryService memoryService
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.promptService = promptService;
        this.songSupplier = songSupplier;
        this.soundFragmentMCPTools = soundFragmentMCPTools;
        this.draftFactory = draftFactory;
        this.memoryService = memoryService;
    }

    public Uni<LiveContainerMcpDTO> getOnline() {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> ACTIVE_STATUSES.contains(station.getStatus()))
                        .filter(station -> !station.getScheduler().isEnabled() || station.isAiControlAllowed())
                        .collect(Collectors.toList())
        ).flatMap(stations -> {
            stations.forEach(station -> clearMessages(station.getSlugName()));
            
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
                        List<LiveRadioStationMcpDTO> validStations = liveStations.stream()
                                .filter(station -> {
                                    if (station.getPrompts() == null || station.getPrompts().isEmpty()) {
                                        log.warn("Station '{}' filtered out: No valid prompts found", station.getName());
                                        addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING,"No valid prompts found");
                                        return false;
                                    }
                                    return true;
                                })
                                .collect(Collectors.toList());
                        container.setRadioStations(validStations);
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
                    liveRadioStation.setSlugName(station.getSlugName());
                    liveRadioStation.setName(station.getLocalizedName().get(agent.getPreferredLang()));
                    liveRadioStation.setDjName(agent.getName());

                    aiDjStatsRequestTracker.put(station.getSlugName(), 
                        new DjRequestInfo(LocalDateTime.now(), agent.getName()));

                    return fetchPrompt(station, agent).flatMap(prompts -> {
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

    private Uni<List<SongPromptMcpDTO>> fetchPrompt(RadioStation station, AiAgent agent) {
        return Uni.combine().all()
                .unis(
                        scriptService.getAllScriptsForBrandWithScenes(station.getId(), SuperUser.build()),
                        memoryService.getByType(station.getSlugName(), MemoryType.MESSAGE.name(), MemoryType.EVENT.name(), MemoryType.CONVERSATION_HISTORY.name())
                )
                .asTuple()
                .flatMap(tuple -> {
                    List<BrandScript> scripts = tuple.getItem1();
                    MemoryResult memoryData = tuple.getItem2();

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
                                List<Prompt> filteredPrompts = prompts.stream()
                                        .filter(p -> p.getLanguageCode() == djLanguage)
                                        .toList();

                                if (filteredPrompts.isEmpty()) {
                                    log.warn("Station '{}': No valid prompt for specific language '{}' found", station.getSlugName(), djLanguage);
                                    addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING, String.format("No valid prompt for specific language '%s' found", djLanguage));
                                    return Uni.createFrom().item(() -> null);
                                }

                                Prompt selectedPrompt = filteredPrompts.get(new Random().nextInt(filteredPrompts.size()));

                                return songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, soundFragmentMCPTools.decideFragmentCount())
                                        .flatMap(songs -> {
                                            List<Uni<SongPromptMcpDTO>> songPromptUnis = songs.stream()
                                                    .map(song -> draftFactory.createDraft(
                                                            song,
                                                            agent,
                                                            station,
                                                            memoryData
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

    private DraftType mapPromptTypeToDraftType(PromptType promptType) {
        return switch (promptType) {
            case BASIC_INTRO -> DraftType.INTRO_DRAFT;
            case USER_MESSAGE -> DraftType.MESSAGE_DRAFT;
            case NEWS, WEATHER, EVENT -> DraftType.NEWS_INTRO_DRAFT;
        };
    }

    public Uni<AiDjStats> getAiDjStats(RadioStation station) {
        return scriptService.getAllScriptsForBrandWithScenes(station.getId(), SuperUser.build())
                .map(scripts -> {
                    if (scripts.isEmpty()) {
                        return null;
                    }

                    LocalDateTime now = LocalDateTime.now();
                    LocalTime currentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    for (BrandScript brandScript : scripts) {
                        List<ScriptScene> scenes = brandScript.getScript().getScenes();
                        for (ScriptScene scene : scenes) {
                            if (isSceneActive(scene, scenes, currentTime, currentDayOfWeek)) {
                                LocalTime sceneStart = scene.getStartTime();
                                LocalTime sceneEnd = findNextSceneStartTime(scene, scenes);
                                int promptCount = scene.getPrompts() != null ? scene.getPrompts().size() : 0;
                                
                                String nextSceneTitle = findNextSceneTitle(scene, scenes);
                                DjRequestInfo requestInfo = aiDjStatsRequestTracker.get(station.getSlugName());

                                LocalDateTime lastRequestTime = null;
                                String djName = null;
                                if (requestInfo != null) {
                                    lastRequestTime = requestInfo.requestTime();
                                    djName = requestInfo.djName();
                                }

                                return new AiDjStats(
                                        scene.getId(),
                                        scene.getTitle(),
                                        sceneStart,
                                        sceneEnd,
                                        promptCount,
                                        nextSceneTitle,
                                        lastRequestTime,
                                        djName,
                                        aiDjMessagesTracker.get(station.getSlugName())
                                );
                            }
                        }
                    }

                    return null;
                });
    }

    private String findNextSceneTitle(ScriptScene currentScene, List<ScriptScene> scenes) {
        LocalTime currentStart = currentScene.getStartTime();
        if (currentStart == null) {
            return null;
        }

        List<ScriptScene> sortedScenes = scenes.stream()
                .filter(s -> s.getStartTime() != null)
                .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                .toList();

        for (ScriptScene scene : sortedScenes) {
            if (scene.getStartTime().isAfter(currentStart)) {
                return scene.getTitle();
            }
        }

        return !sortedScenes.isEmpty() ? sortedScenes.get(0).getTitle() : null;
    }

    public void addMessage(String brandName, AiDjStats.MessageType type, String message) {
        aiDjMessagesTracker.computeIfAbsent(brandName, k -> new ArrayList<>())
                .add(new AiDjStats.StatusMessage(type, message));
    }

    public void clearMessages(String brandName) {
        aiDjMessagesTracker.remove(brandName);
    }
}