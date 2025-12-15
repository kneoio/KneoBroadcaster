package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.LiveContainerDTO;
import io.kneo.broadcaster.dto.aihelper.LiveRadioStationDTO;
import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.aihelper.TtsDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStats;
import io.kneo.broadcaster.model.Action;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.ScriptService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.stream.HlsSegment;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.util.AiHelperUtils;
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

    private Uni<LiveRadioStationDTO> buildLiveRadioStation(IStream stream) {
        LiveRadioStationDTO liveRadioStation = new LiveRadioStationDTO();
        PlaylistManager playlistManager = stream.getStreamManager().getPlaylistManager();
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
                    stream.getSlugName(), queueSize, queuedDurationSec, String.format("%.1f", queuedDurationInMinutes));
            addMessage(stream.getSlugName(), AiDjStats.MessageType.INFO,
                    String.format("The playlist is saturated (size %s, duration %.1f min)", queueSize, queuedDurationInMinutes));

            return Uni.createFrom().item(() -> null);
        } else {
            liveRadioStation.setRadioStationStatus(stream.getStatus());
        }

        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .flatMap(agent -> {
                    LanguageCode broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                    liveRadioStation.setSlugName(stream.getSlugName());
                    String stationName = stream.getLocalizedName().get(broadcastingLanguage);
                    liveRadioStation.setName(stationName);
                    String primaryVoice = AiHelperUtils.resolvePrimaryVoiceId(stream, agent);
                    String additionalInstruction;
                    AiOverriding overriding = stream.getAiOverriding();
                    if (overriding != null) {
                        liveRadioStation.setDjName(String.format("%s overridden as %s", agent.getName(), overriding.getName()));
                        additionalInstruction = "\n\nAdditional instruction: " + overriding.getPrompt();
                    } else {
                        liveRadioStation.setDjName(agent.getName());
                        additionalInstruction = "";
                    }

                    
                    return fetchPrompt(stream, agent, broadcastingLanguage, additionalInstruction).flatMap(tuple -> {
                        if (tuple == null) {
                            return Uni.createFrom().item(liveRadioStation);
                        }
                        List<SongPromptDTO> prompts = tuple.getItem1();

                        liveRadioStation.setPrompts(prompts);
                        liveRadioStation.setInfo(tuple.getItem2());


                        UUID copilotId = agent.getCopilot();

                        return aiAgentService.getDTO(copilotId, SuperUser.build(), LanguageCode.en)
                                .map(copilot -> {
                                    String secondaryVoice = copilot.getPrimaryVoice().getFirst().getId();
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

    private Uni<Tuple2<List<SongPromptDTO>, String>> fetchPrompt(
            IStream stream,
            AiAgent agent,
            LanguageCode broadcastingLanguage,
            String additionalInstruction
    ) {
        return scriptService.getAllScriptsForBrandWithScenes(stream.getId(), SuperUser.build())
                .flatMap(brandScripts -> {

                    if (brandScripts == null || brandScripts.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }

                    String brandSlugName = stream.getSlugName();
                    ZoneId zone = stream.getTimeZone();
                    ZonedDateTime now = ZonedDateTime.now(zone);
                    LocalTime stationCurrentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    List<UUID> allMasterPromptIds = new ArrayList<>();
                    String currentSceneTitle = null;
                    Scene activeScene = null;
                    Map<String, Object> activeUserVariables = null;
                    boolean oneTimeStream = false;

                    BrandScript brandScript = brandScripts.get(new Random().nextInt(brandScripts.size()));

                    List<Scene> scenes = brandScript.getScript().getScenes();
                    boolean useRelativeTiming =
                            brandScript.getScript().getTimingMode() == SceneTimingMode.RELATIVE_TO_STREAM_START;

                    if (useRelativeTiming) {
                        oneTimeStream = true;
                        Scene relativeScene = findActiveSceneByDuration(stream, scenes);
                        if (relativeScene != null) {
                            List<UUID> promptIds = relativeScene.getPrompts() != null
                                    ? relativeScene.getPrompts().stream()
                                    .filter(Action::isActive)
                                    .map(Action::getPromptId)
                                    .toList()
                                    : List.of();
                            allMasterPromptIds.addAll(promptIds);
                            currentSceneTitle = relativeScene.getTitle();
                            activeScene = relativeScene;
                            activeUserVariables = brandScript.getUserVariables();
                        }
                    } else {
                        for (Scene scene : scenes) {
                            if (isSceneActive(
                                    brandSlugName,
                                    zone,
                                    scene,
                                    scenes,
                                    stationCurrentTime,
                                    currentDayOfWeek
                            )) {
                                List<UUID> promptIds = scene.getPrompts() != null
                                        ? scene.getPrompts().stream()
                                        .filter(Action::isActive)
                                        .map(Action::getPromptId)
                                        .toList()
                                        : List.of();
                                allMasterPromptIds.addAll(promptIds);
                                currentSceneTitle = scene.getTitle();
                                activeScene = scene;
                                activeUserVariables = brandScript.getUserVariables();
                            }
                        }
                    }

                    if (activeScene == null) {
                        if (oneTimeStream) {
                            stream.setStatus(RadioStationStatus.OFF_LINE);
                            addMessage(
                                    stream.getSlugName(),
                                    AiDjStats.MessageType.INFO,
                                    "Stream completed - all scenes played"
                            );
                        } else {
                            addMessage(
                                    stream.getSlugName(),
                                    AiDjStats.MessageType.WARNING,
                                    "No active scene found (time:" + stationCurrentTime + ")"
                            );
                        }
                        return Uni.createFrom().item(() -> null);
                    }

                    double effectiveTalkativity = activeScene.getTalkativity();
                    double rate = stream.getPopularityRate();
                    if (rate < 4.0) {
                        double factor = Math.max(0.0, Math.min(1.0, rate / 5.0));
                        effectiveTalkativity =
                                Math.max(0.0, Math.min(1.0, effectiveTalkativity * factor));
                    }

                    if (!oneTimeStream
                            && !activeScene.isOneTimeRun()
                            && AiHelperUtils.shouldPlayJingle(effectiveTalkativity)) {
                        jinglePlaybackHandler.handleJinglePlayback(stream, activeScene);
                        return Uni.createFrom().item(() -> null);
                    }

                    if (allMasterPromptIds.isEmpty()) {
                        addMessage(
                                stream.getSlugName(),
                                AiDjStats.MessageType.WARNING,
                                String.format(
                                        "Active scene '%s' has no prompts",
                                        currentSceneTitle
                                )
                        );
                        return Uni.createFrom().item(() -> null);
                    }

                    List<Uni<Prompt>> promptUnis = allMasterPromptIds.stream()
                            .map(masterId ->
                                    promptService.getById(masterId, SuperUser.build())
                                            .flatMap(masterPrompt -> {
                                                if (masterPrompt.getLanguageCode() == broadcastingLanguage) {
                                                    return Uni.createFrom().item(masterPrompt);
                                                }
                                                return promptService
                                                        .findByMasterAndLanguage(
                                                                masterId,
                                                                broadcastingLanguage,
                                                                false
                                                        )
                                                        .map(p -> p != null ? p : masterPrompt);
                                            })
                            )
                            .toList();

                    String finalCurrentSceneTitle = currentSceneTitle;
                    Scene currentScene = activeScene;
                    Map<String, Object> finalUserVariables = activeUserVariables;

                    return Uni.join().all(promptUnis).andFailFast()
                            .flatMap(prompts ->
                                    fetchSongsForScene(stream, currentScene)
                                            .flatMap(songs -> {
                                                if (songs == null || songs.isEmpty()) {
                                                    return Uni.createFrom().item(
                                                            Tuple2.of(
                                                                    List.of(),
                                                                    finalCurrentSceneTitle
                                                            )
                                                    );
                                                }

                                                Random random = new Random();

                                                List<Uni<SongPromptDTO>> songPromptUnis =
                                                        songs.stream()
                                                                .map(song -> {
                                                                    Prompt selectedPrompt =
                                                                            prompts.get(
                                                                                    random.nextInt(
                                                                                            prompts.size()
                                                                                    )
                                                                            );
                                                                    return draftFactory
                                                                            .createDraft(
                                                                                    song,
                                                                                    agent,
                                                                                    stream,
                                                                                    selectedPrompt.getDraftId(),
                                                                                    broadcastingLanguage,
                                                                                    finalUserVariables
                                                                            )
                                                                            .map(draft ->
                                                                                    new SongPromptDTO(
                                                                                            song.getId(),
                                                                                            draft,
                                                                                            selectedPrompt.getPrompt()
                                                                                                    + additionalInstruction,
                                                                                            selectedPrompt.getPromptType(),
                                                                                            agent.getLlmType(),
                                                                                            agent.getSearchEngineType(),
                                                                                            currentScene.getStartTime(),
                                                                                            currentScene.isOneTimeRun(),
                                                                                            selectedPrompt.isPodcast()
                                                                                    )
                                                                            );
                                                                })
                                                                .toList();

                                                return Uni.join().all(songPromptUnis).andFailFast()
                                                        .map(result ->
                                                                Tuple2.of(
                                                                        result,
                                                                        finalCurrentSceneTitle
                                                                )
                                                        );
                                            })
                            );
                });
    }


    private Uni<List<SoundFragment>> fetchSongsForScene(IStream stream, Scene scene) {
        int quantity = randomizator.decideFragmentCount(stream.getSlugName());
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();

        if (playlistRequest == null) {  //TODO it should be always there, later remove the condition
            return songSupplier.getNextSong(stream.getSlugName(), PlaylistItemType.SONG, quantity);
        }

        WayOfSourcing sourcing = playlistRequest.getSourcing();

        if (sourcing == WayOfSourcing.RANDOM) {
            return songSupplier.getNextSong(stream.getSlugName(), PlaylistItemType.SONG, quantity);
        }

        if (sourcing == WayOfSourcing.QUERY) {
            return songSupplier.getNextSongByQuery(stream.getId(), playlistRequest, quantity);
        }

        if (sourcing == WayOfSourcing.STATIC_LIST) {
            return songSupplier.getNextSongFromStaticList(playlistRequest.getSoundFragments(), quantity);
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

    private Scene findActiveSceneByDuration(IStream stream, List<Scene> scenes) {
        LocalDateTime startTime = stream.getStartTime();
        if (startTime == null) {
            LOGGER.warn("Station '{}': No start time set for relative timing mode", stream.getSlugName());
            return null;
        }
        
        long elapsedSeconds = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now());
        LOGGER.debug("Station '{}': Elapsed time since stream start: {} seconds", stream.getSlugName(), elapsedSeconds);
        
        long cumulativeDuration = 0;
        for (Scene scene : scenes) {
            int sceneDuration = scene.getDurationSeconds();
            if (elapsedSeconds < cumulativeDuration + sceneDuration) {
                LOGGER.debug("Station '{}': Scene '{}' is active (elapsed: {}s, scene range: {}-{}s)",
                        stream.getSlugName(), scene.getTitle(), elapsedSeconds, cumulativeDuration, cumulativeDuration + sceneDuration);
                return scene;
            }
            cumulativeDuration += sceneDuration;
        }
        
        LOGGER.info("Station '{}': All scenes completed (elapsed: {}s, total duration: {}s). Stream should stop.",
                stream.getSlugName(), elapsedSeconds, cumulativeDuration);
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
