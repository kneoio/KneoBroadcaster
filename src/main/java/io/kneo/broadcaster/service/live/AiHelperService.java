package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.aihelper.LiveContainerDTO;
import io.kneo.broadcaster.dto.aihelper.LiveRadioStationDTO;
import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.aihelper.TtsDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.AvailableStationsAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.BrandSoundFragmentAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.ListenerAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.LiveRadioStationStatAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.RadioStationAiDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStats;
import io.kneo.broadcaster.dto.memory.MemoryResult;
import io.kneo.broadcaster.dto.radiostation.RadioStationDTO;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.LanguagePreference;
import io.kneo.broadcaster.model.aiagent.Prompt;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.ScriptService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.stats.StatsAccumulator;
import io.kneo.broadcaster.service.stream.HLSSongStats;
import io.kneo.broadcaster.service.stream.HlsSegment;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.StreamManagerStats;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final io.kneo.broadcaster.service.RadioStationService radioStationService;
    private final io.kneo.broadcaster.service.ListenerService listenerService;
    private final AiAgentService aiAgentService;
    private final ScriptService scriptService;
    private final PromptService promptService;
    private final SongSupplier songSupplier;
    private final DraftFactory draftFactory;
    private final MemoryService memoryService;
    private final JinglePlaybackHandler jinglePlaybackHandler;
    private final Randomizator randomizator;
    private final io.kneo.broadcaster.service.soundfragment.SoundFragmentService soundFragmentService;
    private final io.kneo.broadcaster.service.RefService refService;

    @Inject
    StatsAccumulator statsAccumulator;

    private static final int SCENE_START_SHIFT_MINUTES = 5;

    @Inject
    public AiHelperService(
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService,
            ScriptService scriptService,
            PromptService promptService,
            SongSupplier songSupplier,
            DraftFactory draftFactory,
            MemoryService memoryService,
            JinglePlaybackHandler jinglePlaybackHandler, Randomizator randomizator,
            io.kneo.broadcaster.service.RadioStationService radioStationService,
            io.kneo.broadcaster.service.ListenerService listenerService,
            io.kneo.broadcaster.service.soundfragment.SoundFragmentService soundFragmentService,
            io.kneo.broadcaster.service.RefService refService
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.promptService = promptService;
        this.songSupplier = songSupplier;
        this.draftFactory = draftFactory;
        this.memoryService = memoryService;
        this.jinglePlaybackHandler = jinglePlaybackHandler;
        this.randomizator = randomizator;
        this.radioStationService = radioStationService;
        this.listenerService = listenerService;
        this.soundFragmentService = soundFragmentService;
        this.refService = refService;
    }

    public Uni<LiveContainerDTO> getOnline(List<RadioStationStatus> statuses) {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> statuses.contains(station.getStatus()))
                        .filter(station -> !station.getScheduler().isEnabled() || station.isAiControlAllowed())
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

    public Uni<ListenerAiDTO> getListenerByTelegramName(String telegramName) {
        return listenerService.getAiBrandListenerByTelegramName(telegramName);
    }

    public Uni<AvailableStationsAiDTO> getAllStations(List<RadioStationStatus> statuses, String country, LanguageCode djLanguage, String query) {
        return radioStationService.getAllDTOFiltered(1000, 0, SuperUser.build(), country, query)
                .flatMap(stations -> {
                    if (stations == null || stations.isEmpty()) {
                        AvailableStationsAiDTO container = new AvailableStationsAiDTO();
                        container.setRadioStations(List.of());
                        return Uni.createFrom().item(container);
                    }

                    List<Uni<RadioStationAiDTO>> unis = stations.stream()
                            .map(dto -> {
                                if (statuses != null && !statuses.isEmpty() && (dto.getStatus() == null || !statuses.contains(dto.getStatus()))) {
                                    return Uni.createFrom().<RadioStationAiDTO>nullItem();
                                }

                                if (djLanguage != null) {
                                    if (dto.getAiAgentId() == null) {
                                        return Uni.createFrom().<RadioStationAiDTO>nullItem();
                                    }
                                    return aiAgentService.getById(dto.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .map(agent -> {
                                                if (agent == null || agent.getPreferredLang() == null) {
                                                    return null;
                                                }
                                                boolean supports = agent.getPreferredLang().stream()
                                                        .anyMatch(p -> p.getCode() == djLanguage);
                                                if (!supports) {
                                                    return null;
                                                }
                                                return toRadioStationAiDTO(dto, agent);
                                            })
                                            .onFailure().recoverWithItem(() -> null);
                                } else {
                                    if (dto.getAiAgentId() == null) {
                                        return Uni.createFrom().item(toRadioStationAiDTO(dto, null));
                                    }
                                    return aiAgentService.getById(dto.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .map(agent -> toRadioStationAiDTO(dto, agent))
                                            .onFailure().recoverWithItem(() -> toRadioStationAiDTO(dto, null));
                                }
                            })
                            .collect(Collectors.toList());

                    return (unis.isEmpty() ? Uni.createFrom().item(List.<RadioStationAiDTO>of()) : Uni.join().all(unis).andFailFast())
                            .map(list -> {
                                List<RadioStationAiDTO> stationsList = list.stream()
                                        .filter(java.util.Objects::nonNull)
                                        .collect(Collectors.toList());
                                AvailableStationsAiDTO container = new AvailableStationsAiDTO();
                                container.setRadioStations(stationsList);
                                return container;
                            });
                });
    }

    public Uni<List<BrandSoundFragmentAiDTO>> searchBrandSoundFragmentsForAi(
            String brandName,
            String keyword,
            Integer limit,
            Integer offset
    ) {
        int actualLimit = (limit != null && limit > 0) ? limit : 50;
        int actualOffset = (offset != null && offset >= 0) ? offset : 0;

        return soundFragmentService.getBrandSoundFragmentsBySimilarity(brandName, keyword, actualLimit, actualOffset)
                .chain(brandFragments -> {
                    if (brandFragments == null || brandFragments.isEmpty()) {
                        return Uni.createFrom().item(Collections.<BrandSoundFragmentAiDTO>emptyList());
                    }

                    List<Uni<BrandSoundFragmentAiDTO>> aiDtoUnis = brandFragments.stream()
                            .map(this::mapToBrandSoundFragmentAiDTO)
                            .collect(Collectors.toList());

                    return Uni.join().all(aiDtoUnis).andFailFast();
                });
    }

    public Uni<LiveRadioStationStatAiDTO> getStationLiveStat(String slug) {
        LiveRadioStationStatAiDTO dto = new LiveRadioStationStatAiDTO();
        dto.setSlugName(slug);
        int listeners = 0;
        try {
            long l = statsAccumulator.getCurrentListeners(slug);
            listeners = (int) Math.max(0, Math.min(Integer.MAX_VALUE, l));
        } catch (Exception ignored) {
        }
        dto.setCurrentListeners(listeners);

        return radioStationPool.get(slug)
                .map(station -> {
                    if (station != null && station.getStreamManager() != null) {
                        StreamManagerStats stats = station.getStreamManager().getStats();
                        HLSSongStats songStats = stats.getSongStatistics();
                        if (songStats == null) {
                            dto.setCurrentlyPlaying("playing nothing");
                        } else {
                            dto.setCurrentlyPlaying("playing: " + songStats.songMetadata().getInfo());
                        }

                    }
                    return dto;
                });
    }

    private RadioStationAiDTO toRadioStationAiDTO(RadioStationDTO dto, AiAgent agent) {
        RadioStationAiDTO b = new RadioStationAiDTO();
        b.setLocalizedName(dto.getLocalizedName());
        b.setSlugName(dto.getSlugName());
        b.setCountry(dto.getCountry());
        b.setHlsUrl(dto.getHlsUrl());
        b.setMp3Url(dto.getMp3Url());
        b.setMixplaUrl(dto.getMixplaUrl());
        b.setTimeZone(dto.getTimeZone());
        b.setDescription(dto.getDescription());
        b.setBitRate(dto.getBitRate());
        b.setRadioStationStatus(dto.getStatus());
        if (agent != null) {
            b.setDjName(agent.getName());
            List<LanguageCode> langs = agent.getPreferredLang().stream()
                    .sorted(Comparator.comparingDouble(LanguagePreference::getWeight).reversed())
                    .map(LanguagePreference::getCode)
                    .collect(Collectors.toList());
            b.setAiAgentLang(langs);
        }
        return b;
    }

    private Uni<LiveRadioStationDTO> buildLiveRadioStation(RadioStation station) {
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

                        String primaryVoice = agent.getPrimaryVoice().get(0).getId();
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

    private Uni<Tuple2<List<SongPromptDTO>, String>> fetchPrompt(RadioStation station, AiAgent agent, LanguageCode broadcastingLanguage) {
        return Uni.combine().all()
                .unis(
                        scriptService.getAllScriptsForBrandWithScenes(station.getId(), SuperUser.build()),
                        memoryService.getByType(station.getSlugName(), MemoryType.MESSAGE.name(), MemoryType.EVENT.name(), MemoryType.CONVERSATION_HISTORY.name())
                )
                .asTuple()
                .flatMap(tuple -> {
                    List<BrandScript> brandScripts = tuple.getItem1();
                    MemoryResult memoryData = tuple.getItem2();
                    ZoneId zone = station.getTimeZone();
                    ZonedDateTime now = ZonedDateTime.now(zone);
                    LocalTime stationCurrentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    List<UUID> allMasterPromptIds = new ArrayList<>();
                    String currentSceneTitle = null;
                    Scene activeScene = null;
                    for (BrandScript brandScript : brandScripts) {
                        List<Scene> scenes = brandScript.getScript().getScenes();
                        for (Scene scene : scenes) {
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

                    if (!activeScene.isOneTimeRun() && AiHelperUtils.shouldPlayJingle(activeScene.getTalkativity())) {
                        addMessage(station.getSlugName(), AiDjStats.MessageType.INFO, "Start filler mixing");
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
                                                station.getSlugName(), masterId, masterPrompt.getTitle());

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
                    Scene finalActiveScene = activeScene;
                    return Uni.join().all(promptUnis).andFailFast()
                            .flatMap(prompts -> {
                                LOGGER.debug("Station '{}': Received {} prompts from Uni.join()",
                                        station.getSlugName(), prompts.size());

                                Random random = new Random();

                                return songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, randomizator.decideFragmentCount(station.getSlugName()))
                                        .flatMap(songs -> {
                                            if (songs == null || songs.isEmpty()) {
                                                LOGGER.error("Station '{}': No songs available for prompt generation",
                                                        station.getSlugName());
                                                return Uni.createFrom().item(Tuple2.of(List.<SongPromptDTO>of(), finalCurrentSceneTitle));
                                            }

                                            List<Uni<SongPromptDTO>> songPromptUnis = songs.stream()
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
                                                        ).map(draft -> new SongPromptDTO(
                                                                song.getId(),
                                                                draft,
                                                                selectedPrompt.getPrompt(),
                                                                selectedPrompt.getPromptType(),
                                                                agent.getLlmType(),
                                                                agent.getSearchEngineType(),
                                                                finalActiveScene.getStartTime(),
                                                                finalActiveScene.isOneTimeRun(),
                                                                selectedPrompt.isPodcast()
                                                        ));
                                                    }).collect(Collectors.toList());

                                            return Uni.join().all(songPromptUnis).andFailFast()
                                                    .map(result -> Tuple2.of(result, finalCurrentSceneTitle));
                                        });
                            });
                });
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
        // No distinct later start time today; signal end-of-day by returning null.
        return null;
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
                        List<Scene> scenes = brandScript.getScript().getScenes();
                        for (Scene scene : scenes) {
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

    private String findNextSceneTitle(String stationSlug, int currentDayOfWeek, Scene currentScene, List<Scene> scenes) {
        LocalTime currentStart = currentScene.getStartTime();
        if (currentStart == null) {
            return null;
        }
        List<UUID> usedOneTimes = oneTimeRunTracker.getOrDefault(stationSlug, Collections.emptyList());
        List<Scene> sortedScenes = scenes.stream()
                .filter(s -> s.getStartTime() != null)
                .filter(s -> s.getWeekdays() == null || s.getWeekdays().isEmpty() || s.getWeekdays().contains(currentDayOfWeek))
                .filter(s -> !s.isOneTimeRun() || !usedOneTimes.contains(s.getId()))
                .sorted(Comparator.comparing(Scene::getStartTime))
                .toList();
        for (Scene scene : sortedScenes) {
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


    private Uni<BrandSoundFragmentAiDTO> mapToBrandSoundFragmentAiDTO(BrandSoundFragmentDTO brandFragment) {
        BrandSoundFragmentAiDTO aiDto = new BrandSoundFragmentAiDTO();
        aiDto.setId(brandFragment.getSoundFragmentDTO().getId());
        aiDto.setTitle(brandFragment.getSoundFragmentDTO().getTitle());
        aiDto.setArtist(brandFragment.getSoundFragmentDTO().getArtist());
        aiDto.setAlbum(brandFragment.getSoundFragmentDTO().getAlbum());
        aiDto.setDescription(brandFragment.getSoundFragmentDTO().getDescription());
        aiDto.setPlayedByBrandCount(brandFragment.getPlayedByBrandCount());
        aiDto.setLastTimePlayedByBrand(brandFragment.getLastTimePlayedByBrand());

        List<UUID> genreIds = brandFragment.getSoundFragmentDTO().getGenres();
        List<UUID> labelIds = brandFragment.getSoundFragmentDTO().getLabels();

        Uni<List<String>> genresUni = (genreIds != null && !genreIds.isEmpty())
                ? Uni.join().all(genreIds.stream()
                        .map(genreId -> refService.getById(genreId)
                                .map(genre -> genre.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                                .onFailure().recoverWithItem("Unknown"))
                        .collect(Collectors.toList())).andFailFast()
                : Uni.createFrom().item(Collections.<String>emptyList());

        Uni<List<String>> labelsUni = (labelIds != null && !labelIds.isEmpty())
                ? Uni.join().all(labelIds.stream()
                        .map(labelId -> refService.getById(labelId)
                                .map(label -> label.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                                .onFailure().recoverWithItem("Unknown"))
                        .collect(Collectors.toList())).andFailFast()
                : Uni.createFrom().item(Collections.<String>emptyList());

        return Uni.combine().all().unis(genresUni, labelsUni).asTuple()
                .map(tuple -> {
                    aiDto.setGenres(tuple.getItem1());
                    aiDto.setLabels(tuple.getItem2());
                    return aiDto;
                });
    }
}
