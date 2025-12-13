package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.LiveContainerDTO;
import io.kneo.broadcaster.dto.aihelper.LiveRadioStationDTO;
import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.aihelper.TtsDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStats;
import io.kneo.broadcaster.model.Action;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.StagePlaylist;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.ScriptService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.stream.HlsSegment;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.util.AiHelperUtils;
import io.kneo.broadcaster.util.BrandLogger;
import io.kneo.broadcaster.util.Randomizator;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@ApplicationScoped
public class AirSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(AirSupplier.class);
    private static final int SCENE_START_SHIFT_MINUTES = 5;

    private final RadioStationPool radioStationPool;
    private final AiAgentService aiAgentService;
    private final ScriptService scriptService;
    private final PromptService promptService;
    private final SongSupplier songSupplier;
    private final DraftFactory draftFactory;
    private final JinglePlaybackHandler jinglePlaybackHandler;
    private final Randomizator randomizator;

    private final Map<String, List<UUID>> oneTimeRunTracker = new ConcurrentHashMap<>();
    private final Map<String, List<AiDjStats.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();
    private LocalDate lastReset = LocalDate.now();

    @Inject
    public AirSupplier(
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService,
            ScriptService scriptService,
            PromptService promptService,
            SongSupplier songSupplier,
            DraftFactory draftFactory,
            JinglePlaybackHandler jinglePlaybackHandler,
            Randomizator randomizator
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.promptService = promptService;
        this.songSupplier = songSupplier;
        this.draftFactory = draftFactory;
        this.jinglePlaybackHandler = jinglePlaybackHandler;
        this.randomizator = randomizator;
    }

    public Uni<LiveContainerDTO> getOnline(List<RadioStationStatus> statuses) {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> statuses.contains(station.getStatus()))
                        .collect(Collectors.toList())
        ).flatMap(stations -> {
            stations.forEach(station -> clearDashboardMessages(station.getSlugName()));
            LiveContainerDTO container = new LiveContainerDTO();
            if (stations.isEmpty()) {
                container.setRadioStations(List.of());
                return Uni.createFrom().item(container);
            }
            List<Uni<LiveRadioStationDTO>> stationUnis = stations.stream()
                    .map(this::buildLiveRadioStation)
                    .collect(Collectors.toList());
            return Uni.join().all(stationUnis).andFailFast()
                    .map(liveStations -> {
                        List<LiveRadioStationDTO> validStations = liveStations.stream()
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

    private Uni<LiveRadioStationDTO> buildLiveRadioStation(Brand station) {
        LiveRadioStationDTO liveRadioStation = new LiveRadioStationDTO();
        PlaylistManager playlistManager = station.getStreamManager().getPlaylistManager();
        int queueSize = playlistManager.getPrioritizedQueue().size();
        int queuedDurationSec = playlistManager.getPrioritizedQueue().stream()
                .flatMap(f -> f.getSegments().values().stream())
                .flatMap(ConcurrentLinkedQueue::stream)
                .mapToInt(HlsSegment::getDuration)
                .sum();
        if (queueSize > 1 || queuedDurationSec > 300) {
            double queuedDurationInMinutes = queuedDurationSec / 60.0;
            liveRadioStation.setRadioStationStatus(RadioStationStatus.QUEUE_SATURATED);
            LOGGER.info("Station '{}' is saturated, it will be skip: size={}, duration={}s ({} min)",
                    station.getSlugName(), queueSize, queuedDurationSec, String.format("%.1f", queuedDurationInMinutes));
            addMessage(station.getSlugName(), AiDjStats.MessageType.INFO,
                    String.format("The playlist is saturated (size %s, duration %.1f min)", queueSize, queuedDurationInMinutes));

            return Uni.createFrom().item(() -> null);
        } else {
            liveRadioStation.setRadioStationStatus(station.getStatus());
        }

        return aiAgentService.getById(station.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .flatMap(agent -> {
                    LanguageCode broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                    liveRadioStation.setSlugName(station.getSlugName());
                    String stationName = station.getLocalizedName().get(broadcastingLanguage);
                    liveRadioStation.setName(stationName);
                    String primaryVoice = AiHelperUtils.resolvePrimaryVoiceId(station, agent);
                    String additionalInstruction;
                    AiOverriding overriding = station.getAiOverriding();
                    if (overriding != null) {
                        liveRadioStation.setDjName(String.format("%s overridden as %s", agent.getName(), overriding.getName()));
                        additionalInstruction = "\n\nAdditional instruction: " + overriding.getPrompt();
                    } else {
                        liveRadioStation.setDjName(agent.getName());
                        additionalInstruction = "";
                    }

                    
                    return fetchPrompt(station, agent, broadcastingLanguage, additionalInstruction).flatMap(tuple -> {
                        if (tuple == null) {
                            return Uni.createFrom().item(liveRadioStation);
                        }
                        List<SongPromptDTO> prompts = tuple.getItem1();

                        liveRadioStation.setPrompts(prompts);
                        liveRadioStation.setInfo(tuple.getItem2());


                        UUID copilotId = agent.getCopilot();

                        return aiAgentService.getDTO(copilotId, SuperUser.build(), LanguageCode.en)
                                .map(copilot -> {
                                    String secondaryVoice = copilot.getPrimaryVoice().get(0).getId();
                                    String secondaryVoiceName = copilot.getName();
                                    liveRadioStation.setTts(new TtsDTO(
                                            primaryVoice,
                                            secondaryVoice,
                                            secondaryVoiceName
                                    ));
                                    return liveRadioStation;
                                });
                    });
                });
    }

    private Uni<Tuple2<List<SongPromptDTO>, String>> fetchPrompt(Brand station, AiAgent agent, LanguageCode broadcastingLanguage, String additionalInstruction) {
        return scriptService.getAllScriptsForBrandWithScenes(station.getId(), SuperUser.build())
                .flatMap(brandScripts -> {
                    String brandSlugName = station.getSlugName();
                    ZoneId zone = station.getTimeZone();
                    ZonedDateTime now = ZonedDateTime.now(zone);
                    LocalTime stationCurrentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    List<UUID> allMasterPromptIds = new ArrayList<>();
                    String currentSceneTitle = null;
                    Scene activeScene = null;
                    Map<String, Object> activeUserVariables = null;
                    boolean oneTimeStream = false;
                    for (BrandScript brandScript : brandScripts) {
                        List<Scene> scenes = brandScript.getScript().getScenes();
                        boolean useRelativeTiming = brandScript.getScript().getTimingMode() == SceneTimingMode.RELATIVE_TO_STREAM_START;
                        
                        if (useRelativeTiming) {
                            oneTimeStream = true;
                            Scene relativeScene = findActiveSceneByDuration(station, scenes);
                            if (relativeScene != null) {
                                List<UUID> promptIds = relativeScene.getPrompts() != null ?
                                        relativeScene.getPrompts().stream()
                                                .filter(Action::isActive)
                                                .map(Action::getPromptId)
                                                .toList() : List.of();
                                allMasterPromptIds.addAll(promptIds);
                                currentSceneTitle = relativeScene.getTitle();
                                activeScene = relativeScene;
                                activeUserVariables = brandScript.getUserVariables();
                                LOGGER.debug("Station '{}': Active scene '{}' found (relative timing) with {} prompts",
                                        brandSlugName, relativeScene.getTitle(), promptIds.size());
                            }
                        } else {
                            for (Scene scene : scenes) {
                                if (isSceneActive(brandSlugName, zone, scene, scenes, stationCurrentTime, currentDayOfWeek)) {
                                    List<UUID> promptIds = scene.getPrompts() != null ?
                                            scene.getPrompts().stream()
                                                    .filter(Action::isActive)
                                                    .map(Action::getPromptId)
                                                    .toList() : List.of();
                                    allMasterPromptIds.addAll(promptIds);
                                    currentSceneTitle = scene.getTitle();
                                    activeScene = scene;
                                    activeUserVariables = brandScript.getUserVariables();
                                    LOGGER.debug("Station '{}': Active scene '{}' found with {} prompts",
                                            brandSlugName, scene.getTitle(), promptIds.size());
                                }
                            }
                        }
                    }

                    if (activeScene == null) {
                        if (oneTimeStream) {
                            LOGGER.info("Station '{}': All scenes completed in relative timing mode. Setting status to OFF_LINE.", station.getSlugName());
                            station.setStatus(RadioStationStatus.OFF_LINE);
                            addMessage(station.getSlugName(), AiDjStats.MessageType.INFO, "Stream completed - all scenes played");
                        } else {
                            LOGGER.warn("Station '{}' skipped: No active scene found for current time {} (day {})", station.getSlugName(), stationCurrentTime, currentDayOfWeek);
                            addMessage(station.getSlugName(), AiDjStats.MessageType.WARNING, "No active scene found (time:" + stationCurrentTime + ")");
                        }
                        return Uni.createFrom().item(() -> null);
                    }

                    double effectiveTalkativity = activeScene.getTalkativity();
                    double rate = station.getPopularityRate();
                    if (rate < 4.0) {
                        double factor = Math.max(0.0, Math.min(1.0, rate / 5.0));
                        effectiveTalkativity = Math.max(0.0, Math.min(1.0, effectiveTalkativity * factor));
                    }

                    BrandLogger.logActivity(brandSlugName, "decision", "effectiveTalkativity : %s", effectiveTalkativity);
                    if (!oneTimeStream && !activeScene.isOneTimeRun() && AiHelperUtils.shouldPlayJingle(effectiveTalkativity)) {
                        addMessage(station.getSlugName(), AiDjStats.MessageType.INFO, "mixing ...");
                        jinglePlaybackHandler.handleJinglePlayback(station, activeScene);
                        return Uni.createFrom().item(() -> null);
                    } else {
                        addMessage(station.getSlugName(), AiDjStats.MessageType.INFO, "DJ is curating ...");
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
                                                station.getSlugName(), masterId, masterPrompt.getTitle());

                                        if (masterPrompt.getLanguageCode() == broadcastingLanguage) {
                                            LOGGER.debug("Station '{}': Using master prompt directly (language matches)", station.getSlugName());
                                            return Uni.createFrom().item(masterPrompt);
                                        }

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
                    Scene finalActiveScene = activeScene;
                    Map<String, Object> finalUserVariables = activeUserVariables;
                    return Uni.join().all(promptUnis).andFailFast()
                            .flatMap(prompts -> {
                                LOGGER.debug("Station '{}': Received {} prompts from Uni.join()", station.getSlugName(), prompts.size());
                                Random random = new Random();
                                return fetchSongsForScene(station, finalActiveScene)
                                        .flatMap(songs -> {
                                            if (songs == null || songs.isEmpty()) {
                                                LOGGER.error("Station '{}': No songs available for prompt generation", station.getSlugName());
                                                return Uni.createFrom().item(Tuple2.of(List.<SongPromptDTO>of(), finalCurrentSceneTitle));
                                            }

                                            List<Uni<SongPromptDTO>> songPromptUnis = songs.stream()
                                                    .map(song -> {
                                                        Prompt selectedPrompt = prompts.get(random.nextInt(prompts.size()));
                                                        LOGGER.debug("Station '{}': Selected prompt '{}' for song '{}'",
                                                                station.getSlugName(), selectedPrompt.getTitle(), song.getTitle());

                                                        return draftFactory.createDraft(
                                                                song,
                                                                agent,
                                                                station,
                                                                selectedPrompt.getDraftId(),
                                                                broadcastingLanguage,
                                                                finalUserVariables
                                                        ).map(draft -> {
                                                            return new SongPromptDTO(
                                                                    song.getId(),
                                                                    draft,
                                                                    selectedPrompt.getPrompt() + additionalInstruction,
                                                                    selectedPrompt.getPromptType(),
                                                                    agent.getLlmType(),
                                                                    agent.getSearchEngineType(),
                                                                    finalActiveScene.getStartTime(),
                                                                    finalActiveScene.isOneTimeRun(),
                                                                    selectedPrompt.isPodcast()
                                                            );
                                                        });
                                                    }).collect(Collectors.toList());

                                            return Uni.join().all(songPromptUnis).andFailFast()
                                                    .map(result -> Tuple2.of(result, finalCurrentSceneTitle));
                                        });
                            });
                });
    }

    private Uni<List<SoundFragment>> fetchSongsForScene(Brand station, Scene scene) {
        int quantity = randomizator.decideFragmentCount(station.getSlugName());
        StagePlaylist stagePlaylist = scene.getStagePlaylist();

        if (stagePlaylist == null) {
            return songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, quantity);
        }

        WayOfSourcing sourcing = stagePlaylist.getSourcing();

        if (sourcing == WayOfSourcing.RANDOM) {
            return songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, quantity);
        }

        if (sourcing == WayOfSourcing.QUERY) {
            return songSupplier.getNextSongByQuery(station.getId(), stagePlaylist, quantity);
        }

        if (sourcing == WayOfSourcing.STATIC_LIST) {
            return songSupplier.getNextSongFromStaticList(stagePlaylist.getSoundFragments(), quantity);
        }

        throw new IllegalStateException("Unknown sourcing type: " + sourcing);
    }

    private boolean isSceneActive(String stationSlug, ZoneId zone, Scene scene, List<Scene> allScenes, LocalTime currentTime, int currentDayOfWeek) {
        if (!LocalDate.now(zone).equals(lastReset)) {
            oneTimeRunTracker.clear();
            lastReset = LocalDate.now(zone);
        }

        List<Integer> weekdays = scene.getWeekdays();
        if (weekdays != null && !weekdays.isEmpty() && !weekdays.contains(currentDayOfWeek)) {
            return false;
        }

        if (scene.getStartTime() == null) {
            return markIfOneTime(stationSlug, scene);
        }

        LocalTime sceneStart = scene.getStartTime().minusMinutes(SCENE_START_SHIFT_MINUTES);
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

    private boolean markIfOneTime(String stationSlug, Scene scene) {
        if (scene.isOneTimeRun()) {
            List<UUID> used = oneTimeRunTracker.computeIfAbsent(stationSlug, k -> new ArrayList<>());
            if (used.contains(scene.getId())) {
                return false;
            }
            used.add(scene.getId());
        }
        return true;
    }

    private Scene findActiveSceneByDuration(Brand station, List<Scene> scenes) {
        LocalDateTime startTime = station.getStartTime();
        if (startTime == null) {
            LOGGER.warn("Station '{}': No start time set for relative timing mode", station.getSlugName());
            return null;
        }
        
        long elapsedSeconds = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now());
        LOGGER.debug("Station '{}': Elapsed time since stream start: {} seconds", station.getSlugName(), elapsedSeconds);
        
        long cumulativeDuration = 0;
        for (Scene scene : scenes) {
            int sceneDuration = scene.getDurationSeconds();
            if (elapsedSeconds < cumulativeDuration + sceneDuration) {
                LOGGER.debug("Station '{}': Scene '{}' is active (elapsed: {}s, scene range: {}-{}s)",
                        station.getSlugName(), scene.getTitle(), elapsedSeconds, cumulativeDuration, cumulativeDuration + sceneDuration);
                return scene;
            }
            cumulativeDuration += sceneDuration;
        }
        
        LOGGER.info("Station '{}': All scenes completed (elapsed: {}s, total duration: {}s). Stream should stop.",
                station.getSlugName(), elapsedSeconds, cumulativeDuration);
        return null;
    }

    private LocalTime findNextSceneStartTime(String stationSlug, int currentDayOfWeek, Scene currentScene, List<Scene> scenes) {
        LocalTime currentStart = currentScene.getStartTime();
        if (currentStart == null) {
            return null;
        }
        List<UUID> usedOneTimes = oneTimeRunTracker.getOrDefault(stationSlug, Collections.emptyList());
        List<LocalTime> sortedTimes = scenes.stream()
                .filter(s -> s.getStartTime() != null)
                .filter(s -> s.getWeekdays() == null || s.getWeekdays().isEmpty() || s.getWeekdays().contains(currentDayOfWeek))
                .filter(s -> !s.isOneTimeRun() || !usedOneTimes.contains(s.getId()))
                .map(Scene::getStartTime)
                .sorted()
                .distinct()
                .toList();
        for (LocalTime time : sortedTimes) {
            if (time.isAfter(currentStart)) {
                return time;
            }
        }
        return null;
    }

    private void clearDashboardMessages(String stationSlug) {
        aiDjMessagesTracker.remove(stationSlug);
    }

    private void addMessage(String stationSlug, AiDjStats.MessageType type, String message) {
        aiDjMessagesTracker.computeIfAbsent(stationSlug, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new AiDjStats.StatusMessage(type, message));
    }
}
