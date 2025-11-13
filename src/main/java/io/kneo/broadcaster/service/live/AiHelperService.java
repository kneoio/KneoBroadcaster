package io.kneo.broadcaster.service.live;

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
import io.kneo.broadcaster.model.ai.LanguagePreference;
import io.kneo.broadcaster.model.ai.Prompt;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.ScriptService;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperService.class);

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
    private final JinglePlaybackHandler jinglePlaybackHandler;

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
            MemoryService memoryService,
            JinglePlaybackHandler jinglePlaybackHandler
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.promptService = promptService;
        this.songSupplier = songSupplier;
        this.soundFragmentMCPTools = soundFragmentMCPTools;
        this.draftFactory = draftFactory;
        this.memoryService = memoryService;
        this.jinglePlaybackHandler = jinglePlaybackHandler;
    }

    public Uni<LiveContainerMcpDTO> getOnline() {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> ACTIVE_STATUSES.contains(station.getStatus()))
                        .filter(station -> !station.getScheduler().isEnabled() || station.isAiControlAllowed())
                        .collect(Collectors.toList())
        ).flatMap(stations -> {
            stations.forEach(station -> clearDashboardMessages(station.getSlugName()));
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
                                .filter(liveStation -> {
                                    if (liveStation == null) {
                                        return false;
                                    } else if (liveStation.getPrompts() == null || liveStation.getPrompts().isEmpty()) {
                                            LOGGER.debug("Station '{}' filtered out: No active prompts", liveStation.getSlugName());
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
        int queueSize = station.getStreamManager().getPlaylistManager().getPrioritizedQueue().size();
        LOGGER.info("Station '{}' has queue size: {}", station.getSlugName(), queueSize);
        if (queueSize > 1) {
            liveRadioStation.setRadioStationStatus(RadioStationStatus.QUEUE_SATURATED);
            LOGGER.info("Station '{}' is saturated, it will be skip: {}", station.getSlugName(), queueSize);
            addMessage(station.getSlugName(), AiDjStats.MessageType.INFO,
                    String.format("The playlist is saturated (size %s)", queueSize));

            return Uni.createFrom().item(() -> null);
        } else {
            liveRadioStation.setRadioStationStatus(station.getStatus());
        }

        return aiAgentService.getById(station.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .flatMap(agent -> {
                    LanguageCode broadcastingLanguage = selectLanguageByWeight(agent);
                    liveRadioStation.setSlugName(station.getSlugName());
                    String stationName = station.getLocalizedName().get(broadcastingLanguage);
                    if (stationName == null) {  //it might happen if dj speaks language that not represented in the localized names
                        stationName = station.getLocalizedName().get(LanguageCode.en);
                        LOGGER.warn("Station '{}': No localized name for language '{}'", station.getSlugName(), broadcastingLanguage);
                    }
                    liveRadioStation.setName(stationName);
                    liveRadioStation.setDjName(agent.getName());

                    aiDjStatsRequestTracker.put(station.getSlugName(),
                            new DjRequestInfo(LocalDateTime.now(), agent.getName()));

                    return fetchPrompt(station, agent, broadcastingLanguage).flatMap(tuple -> {
                        if (tuple == null) {
                            //LOGGER.warn("Station '{}' skipped: fetchPrompt() returned no data", station.getSlugName());
                            // addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING, "No prompt data available");
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

    private Uni<Tuple2<List<SongPromptMcpDTO>, String>> fetchPrompt(RadioStation station, AiAgent agent, LanguageCode broadcastingLanguage) {
        return Uni.combine().all()
                .unis(
                        scriptService.getAllScriptsForBrandWithScenes(station.getId(), SuperUser.build()),
                        memoryService.getByType(station.getSlugName(), MemoryType.MESSAGE.name(), MemoryType.EVENT.name(), MemoryType.CONVERSATION_HISTORY.name())
                )
                .asTuple()
                .flatMap(tuple -> {
                    List<BrandScript> brandScripts = tuple.getItem1();
                    MemoryResult memoryData = tuple.getItem2();
                    if (brandScripts.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }

                    ZoneId zone = station.getTimeZone();
                    ZonedDateTime now = ZonedDateTime.now(zone);
                    LocalTime stationCurrentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    List<UUID> allMasterPromptIds = new ArrayList<>();
                    String currentSceneTitle = null;
                    ScriptScene activeScene = null;
                    for (BrandScript brandScript : brandScripts) {
                        List<ScriptScene> scenes = brandScript.getScript().getScenes();
                        for (ScriptScene scene : scenes) {
                            if (isSceneActive(station.getSlugName(), zone, scene, scenes, stationCurrentTime, currentDayOfWeek)) {
                                allMasterPromptIds.addAll(scene.getPrompts());
                                currentSceneTitle = scene.getTitle();
                                activeScene = scene;
                                LOGGER.debug("Station '{}': Active scene '{}' found with {} prompts", 
                                        station.getSlugName(), scene.getTitle(), scene.getPrompts().size());
                            }
                        }
                    }

                    if (activeScene == null) {
                        LOGGER.warn("Station '{}' skipped: No active scene found for current time {} (day {})", station.getSlugName(), stationCurrentTime, currentDayOfWeek);
                        addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING, "No active scene found (time:" + stationCurrentTime + ")");
                        return Uni.createFrom().item(() -> null);
                    }

                    if (!activeScene.isOneTimeRun() && shouldPlayJingle(activeScene.getTalkativity())) {
                        //addMessage(station.getSlugName(), AiDjStats.MessageType.INFO, "Start mixing");
                        jinglePlaybackHandler.handleJinglePlayback(station, activeScene);
                        return Uni.createFrom().item(() -> null);
                    }

                    if (allMasterPromptIds.isEmpty()) {
                        LOGGER.warn("Station '{}' skipped: Active scene '{}' has no prompts configured", 
                                station.getSlugName(), currentSceneTitle);
                        addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING, 
                                String.format("Active scene '%s' has no prompts", currentSceneTitle));
                        return Uni.createFrom().item(() -> null);
                    }

                    LOGGER.debug("Station '{}': Found {} master prompt IDs in active scene, DJ language: {}", 
                            station.getSlugName(), allMasterPromptIds.size(), broadcastingLanguage);
                    
                    List<Uni<Prompt>> promptUnis = allMasterPromptIds.stream()
                            .map(masterId -> promptService.getById(masterId, SuperUser.build())
                                    .flatMap(masterPrompt -> {
                                        LOGGER.debug("Station '{}': Master prompt ID={}, title={}",
                                                station.getSlugName(), masterId,  masterPrompt.getTitle());
                                        
                                        if (masterPrompt.getLanguageCode() == broadcastingLanguage) {
                                            LOGGER.debug("Station '{}': Using master prompt directly (language matches)", station.getSlugName());
                                            return Uni.createFrom().item(masterPrompt);
                                        }
                                        
                                        LOGGER.debug("Station '{}': Looking for translation of master ID={} to language={}", 
                                                station.getSlugName(), masterId, broadcastingLanguage);
                                        
                                        return promptService.findByMasterAndLanguage(masterId, broadcastingLanguage, false)
                                                .map(translatedPrompt -> {
                                                    if (translatedPrompt != null) {
                                                        LOGGER.debug("Station '{}': Found translation ID={}, title={}", 
                                                                station.getSlugName(), translatedPrompt.getId(), translatedPrompt.getTitle());
                                                        return translatedPrompt;
                                                    } else {
                                                        LOGGER.warn("Station '{}': No translation found for master ID={} in language={}, falling back to master (language={})", 
                                                                station.getSlugName(), masterId, broadcastingLanguage, masterPrompt.getLanguageCode());
                                                        return masterPrompt;
                                                    }
                                                });
                                    })
                                    .onFailure().invoke(t -> LOGGER.error("Station '{}': Failed to fetch prompt for master ID={}: {}", 
                                            station.getSlugName(), masterId, t.getMessage(), t)))
                            .collect(Collectors.toList());

                    String finalCurrentSceneTitle = currentSceneTitle;
                    ScriptScene finalActiveScene = activeScene;
                    return Uni.join().all(promptUnis).andFailFast()
                            .flatMap(prompts -> {
                                LOGGER.debug("Station '{}': Received {} prompts from Uni.join()", 
                                        station.getSlugName(), prompts.size());

                                Random random = new Random();
                                
                                return songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, soundFragmentMCPTools.decideFragmentCount())
                                        .flatMap(songs -> {
                                            List<Uni<SongPromptMcpDTO>> songPromptUnis = songs.stream()
                                                    .map(song -> {
                                                        Prompt selectedPrompt = prompts.get(random.nextInt(prompts.size()));
                                                        //addMessage(station.getSlugName(), AiDjStats.MessageType.INFO, String.format("DJ session started (%s)", song.getMetadata()));
                                                        LOGGER.debug("Station '{}': Selected prompt '{}' for song '{}'", 
                                                                station.getSlugName(), selectedPrompt.getTitle(), song.getTitle());
                                                        
                                                        return draftFactory.createDraft(
                                                                song,
                                                                agent,
                                                                station,
                                                                memoryData,
                                                                selectedPrompt.getDraftId(),
                                                                broadcastingLanguage
                                                        ).map(draft -> new SongPromptMcpDTO(
                                                                song.getId(),
                                                                draft,
                                                                selectedPrompt.getPrompt(),
                                                                selectedPrompt.getPromptType(),
                                                                agent.getLlmType(),
                                                                agent.getSearchEngineType(),
                                                                finalActiveScene.getStartTime(),
                                                                finalActiveScene.isOneTimeRun()
                                                        ));
                                                    }).collect(Collectors.toList());

                                            return Uni.join().all(songPromptUnis).andFailFast()
                                                    .map(result -> Tuple2.of(result, finalCurrentSceneTitle));
                                        });
                            });
                });
    }

    private boolean shouldPlayJingle(double talkativity) {
        double jingleProbability = 1.0 - talkativity;
        double randomValue = new Random().nextDouble();
        return randomValue < jingleProbability;
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
        LocalTime nextSceneStart = findNextSceneStartTime(stationSlug, currentDayOfWeek, scene, allScenes);

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

    private LocalTime findNextSceneStartTime(String stationSlug, int currentDayOfWeek, ScriptScene currentScene, List<ScriptScene> scenes) {
        LocalTime currentStart = currentScene.getStartTime();
        if (currentStart == null) {
            return null;
        }
        List<UUID> usedOneTimes = oneTimeRunTracker.getOrDefault(stationSlug, Collections.emptyList());
        List<LocalTime> sortedTimes = scenes.stream()
                .filter(s -> s.getStartTime() != null)
                .filter(s -> s.getWeekdays() == null || s.getWeekdays().isEmpty() || s.getWeekdays().contains(currentDayOfWeek))
                .filter(s -> !s.isOneTimeRun() || !usedOneTimes.contains(s.getId()))
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

    public Uni<AiDjStats> getAiDjStats(RadioStation station) {
        return scriptService.getAllScriptsForBrandWithScenes(station.getId(), SuperUser.build())
                .map(scripts -> {
                    if (scripts.isEmpty()) {
                        return null;
                    }
                    ZonedDateTime now = ZonedDateTime.now(station.getTimeZone());
                    LocalTime currentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    for (BrandScript brandScript : scripts) {
                        List<ScriptScene> scenes = brandScript.getScript().getScenes();
                        for (ScriptScene scene : scenes) {
                            if (isSceneActive(station.getSlugName(), station.getTimeZone(), scene, scenes, currentTime, currentDayOfWeek)) {
                                LocalTime sceneStart = scene.getStartTime();
                                LocalTime sceneEnd = findNextSceneStartTime(station.getSlugName(), currentDayOfWeek, scene, scenes);
                                int promptCount = scene.getPrompts() != null ? scene.getPrompts().size() : 0;
                                String nextSceneTitle = findNextSceneTitle(station.getSlugName(), currentDayOfWeek, scene, scenes);
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

    private String findNextSceneTitle(String stationSlug, int currentDayOfWeek, ScriptScene currentScene, List<ScriptScene> scenes) {
        LocalTime currentStart = currentScene.getStartTime();
        if (currentStart == null) {
            return null;
        }
        List<UUID> usedOneTimes = oneTimeRunTracker.getOrDefault(stationSlug, Collections.emptyList());
        List<ScriptScene> sortedScenes = scenes.stream()
                .filter(s -> s.getStartTime() != null)
                .filter(s -> s.getWeekdays() == null || s.getWeekdays().isEmpty() || s.getWeekdays().contains(currentDayOfWeek))
                .filter(s -> !s.isOneTimeRun() || !usedOneTimes.contains(s.getId()))
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

    public void clearDashboardMessages(String brandName) {
        aiDjMessagesTracker.remove(brandName);
    }

    private LanguageCode selectLanguageByWeight(AiAgent agent) {
        List<LanguagePreference> preferences = agent.getPreferredLang();
        if (preferences == null || preferences.isEmpty()) {
            LOGGER.warn("Agent '{}' has no language preferences, defaulting to English", agent.getName());
            return LanguageCode.en;
        }

        if (preferences.size() == 1) {
            return preferences.get(0).getCode();
        }

        double totalWeight = preferences.stream()
                .mapToDouble(LanguagePreference::getWeight)
                .sum();

        if (totalWeight <= 0) {
            LOGGER.warn("Agent '{}' has invalid weights (total <= 0), using first language", agent.getName());
            return preferences.get(0).getCode();
        }

        double randomValue = new Random().nextDouble() * totalWeight;
        double cumulativeWeight = 0;
        for (LanguagePreference pref : preferences) {
            cumulativeWeight += pref.getWeight();
            if (randomValue <= cumulativeWeight) {
                return pref.getCode();
            }
        }

        return preferences.get(0).getCode();
    }
}
