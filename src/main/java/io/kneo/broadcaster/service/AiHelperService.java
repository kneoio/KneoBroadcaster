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
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger log = LoggerFactory.getLogger(AiHelperService.class);

    public record DjRequestInfo(LocalDateTime requestTime, String djName) {
    }

    private final Map<String, DjRequestInfo> aiDjStatsRequestTracker = new ConcurrentHashMap<>();
    private final Map<String, List<AiDjStats.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();
    private final Map<String, List<UUID>> oneTimeRunTracker = new ConcurrentHashMap<>();
    private LocalDate lastReset = LocalDate.now();

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
            RadioStationStatus.WARMING_UP,
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
                                        addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING, "No valid prompts found");
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

                    return fetchPrompt(station, agent).flatMap(tuple -> {
                        if (tuple == null) {
                            log.warn("Station '{}' skipped: fetchPrompt() returned no data", station.getSlugName());
                            addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING, "No prompt data available");
                            return Uni.createFrom().item(liveRadioStation);
                        }
                        var prompts = tuple.getItem1();

                        liveRadioStation.setPrompts(prompts);
                        liveRadioStation.setInfo(tuple.getItem2());

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

    private Uni<Tuple2<List<SongPromptMcpDTO>, String>> fetchPrompt(RadioStation station, AiAgent agent) {
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

                    ZoneId zone = station.getTimeZone();
                    ZonedDateTime now = ZonedDateTime.now(zone);
                    LocalTime currentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    List<UUID> allPromptIds = new ArrayList<>();
                    String currentSceneTitle = null;
                    for (BrandScript brandScript : scripts) {
                        List<ScriptScene> scenes = brandScript.getScript().getScenes();
                        for (ScriptScene scene : scenes) {
                            if (isSceneActive(station.getSlugName(), zone, scene, scenes, currentTime, currentDayOfWeek)) {
                                allPromptIds.addAll(scene.getPrompts());
                                currentSceneTitle = scene.getTitle();
                            }
                        }
                    }

                    if (allPromptIds.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }

                    List<Uni<Prompt>> promptUnis = allPromptIds.stream()
                            .map(id -> promptService.getById(id, SuperUser.build()))
                            .collect(Collectors.toList());

                    String finalCurrentSceneTitle = currentSceneTitle;
                    return Uni.join().all(promptUnis).andFailFast()
                            .flatMap(prompts -> {
                                LanguageCode djLanguage = agent.getPreferredLang();
                                List<Prompt> filteredPrompts = prompts.stream()
                                        .filter(p -> p.getLanguageCode() == djLanguage)
                                        .toList();

                                if (filteredPrompts.isEmpty()) {
                                    log.warn("Station '{}': No valid prompt for specific language '{}' found", station.getSlugName(), djLanguage);
                                    addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING,
                                            String.format("No valid prompt for specific language '%s' found", djLanguage));
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
                                                            memoryData,
                                                            selectedPrompt.getDraftId()
                                                    ).map(draft -> new SongPromptMcpDTO(
                                                            song.getId(),
                                                            draft,
                                                            selectedPrompt.getPrompt(),
                                                            selectedPrompt.getPromptType(),
                                                            agent.getLlmType(),
                                                            agent.getSearchEngineType()
                                                    ))).collect(Collectors.toList());

                                            return Uni.join().all(songPromptUnis).andFailFast()
                                                    .map(result -> Tuple2.of(result, finalCurrentSceneTitle));
                                        });
                            });
                });
    }

    private boolean isSceneActive(String stationSlug, ZoneId zone, ScriptScene scene, List<ScriptScene> allScenes, LocalTime currentTime, int currentDayOfWeek) {
        if (!LocalDate.now(zone).equals(lastReset)) {
            oneTimeRunTracker.clear();
            lastReset = LocalDate.now(zone);
        }

        if (!scene.getWeekdays().isEmpty() && !scene.getWeekdays().contains(currentDayOfWeek)) {
            return false;
        }

        if (scene.getStartTime() == null) {
            return markIfOneTime(stationSlug, scene);
        }

        LocalTime sceneStart = scene.getStartTime();
        LocalTime nextSceneStart = findNextSceneStartTime(scene, allScenes);

        boolean active;
        if (nextSceneStart != null && nextSceneStart.isAfter(sceneStart)) {
            active = !currentTime.isBefore(sceneStart) && currentTime.isBefore(nextSceneStart);
        } else if (nextSceneStart != null) {
            active = !currentTime.isBefore(sceneStart) || currentTime.isBefore(nextSceneStart);
        } else {
            active = !currentTime.isBefore(sceneStart);
        }

        return active && markIfOneTime(stationSlug, scene);
    }


    private boolean markIfOneTime(String stationSlug, ScriptScene scene) {
        if (scene.isOneTimeRun()) {
            List<UUID> used = oneTimeRunTracker.computeIfAbsent(stationSlug, k -> new ArrayList<>());
            if (used.contains(scene.getId())) {
                return false;
            }
            used.add(scene.getId());
        }
        return true;
    }

    private LocalTime findNextSceneStartTime(ScriptScene currentScene, List<ScriptScene> scenesWithTime) {
        LocalTime currentStart = currentScene.getStartTime();
        List<LocalTime> sortedTimes = scenesWithTime.stream()
                .map(ScriptScene::getStartTime)
                .filter(Objects::nonNull)
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
                            if (isSceneActive(station.getSlugName(), station.getTimeZone(), scene, scenes, currentTime, currentDayOfWeek)) {
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
                .sorted(Comparator.comparing(ScriptScene::getStartTime))
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
