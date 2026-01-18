package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.AvailableStationsAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.BrandSoundFragmentAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.ListenerAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.LiveRadioStationStatAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.RadioStationAiDTO;
import io.kneo.broadcaster.dto.dashboard.AiDjStatsDTO;
import io.kneo.broadcaster.dto.radiostation.AiOverridingDTO;
import io.kneo.broadcaster.dto.radiostation.BrandDTO;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.LivePrompt;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.LanguagePreference;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.repository.ListenersRepository;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.ListenerService;
import io.kneo.broadcaster.service.ScriptService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stats.StatsAccumulator;
import io.kneo.broadcaster.service.stream.HLSSongStats;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.StreamManagerStats;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.officeframe.service.GenreService;
import io.kneo.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperService.class);

    public record DjRequestInfo(LocalDateTime requestTime, String djName) {
    }

    private Map<String, DjRequestInfo> aiDjStatsRequestTracker = new ConcurrentHashMap<>();
    private final Map<String, List<AiDjStatsDTO.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();
    private final Map<String, List<UUID>> oneTimeRunTracker = new ConcurrentHashMap<>();
    private LocalDate lastReset = LocalDate.now();

    private final RadioStationPool radioStationPool;
    private final BrandService brandService;
    private final ListenerService listenerService;
    private final ListenersRepository listenerRepository;
    private final AiAgentService aiAgentService;
    private final ScriptService scriptService;
    private final SoundFragmentService soundFragmentService;
    private final GenreService genreService;
    private final LabelService labelService;

    @Inject
    StatsAccumulator statsAccumulator;

    private static final int SCENE_START_SHIFT_MINUTES = 10;

    @Inject
    public AiHelperService(
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService,
            ScriptService scriptService,
            BrandService brandService,
            ListenerService listenerService,
            ListenersRepository listenerRepository,
            SoundFragmentService soundFragmentService,
            GenreService genreService,
            LabelService labelService
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.brandService = brandService;
        this.listenerService = listenerService;
        this.listenerRepository = listenerRepository;
        this.soundFragmentService = soundFragmentService;
        this.genreService = genreService;
        this.labelService = labelService;
    }

    public Uni<ListenerAiDTO> getListenerByTelegramName(String telegramName) {
        return listenerRepository.findByUserDataField("telegram_name", telegramName)
                .onItem().transform(listener -> {
                    if (listener == null) {
                        return null;
                    }
                    ListenerAiDTO aiDto = new ListenerAiDTO();
                    aiDto.setTelegramName(telegramName);
                    aiDto.setLocalizedName(listener.getLocalizedName());
                    aiDto.setNickName(listener.getNickName());
                    return aiDto;
                });
    }

    public Uni<AvailableStationsAiDTO> getAllStations(List<StreamStatus> statuses, String country, LanguageTag djLanguage, String query) {
        return brandService.getAllDTO(1000, 0, SuperUser.build(), country, query)
                .flatMap(stations -> {
                    if (stations == null || stations.isEmpty()) {
                        AvailableStationsAiDTO container = new AvailableStationsAiDTO();
                        container.setRadioStations(List.of());
                        return Uni.createFrom().item(container);
                    }

                    List<Uni<RadioStationAiDTO>> unis = stations.stream()
                            .map(dto -> {
                                if (statuses != null && !statuses.contains(dto.getStatus())) {
                                    return Uni.createFrom().<RadioStationAiDTO>nullItem();
                                }

                                if (djLanguage != null) {
                                    if (dto.getAiAgentId() == null) {
                                        return Uni.createFrom().<RadioStationAiDTO>nullItem();
                                    }
                                    return aiAgentService.getById(dto.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .map(agent -> {
                                                boolean supports = agent.getPreferredLang().stream()
                                                        .anyMatch(p -> p.getLanguageTag() == djLanguage);
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
                                List<RadioStationAiDTO> stationsList = new ArrayList<>(list);
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

    private RadioStationAiDTO toRadioStationAiDTO(BrandDTO brandDTO, AiAgent agent) {
        RadioStationAiDTO b = new RadioStationAiDTO();
        b.setLocalizedName(brandDTO.getLocalizedName());
        b.setSlugName(brandDTO.getSlugName());
        b.setCountry(brandDTO.getCountry());
        b.setHlsUrl(brandDTO.getHlsUrl());
        b.setMp3Url(brandDTO.getMp3Url());
        b.setMixplaUrl(brandDTO.getMixplaUrl());
        b.setTimeZone(brandDTO.getTimeZone());
        b.setDescription(brandDTO.getDescription());
        b.setBitRate(brandDTO.getBitRate());
        b.setStreamStatus(brandDTO.getStatus());
        if (agent != null) {
            b.setDjName(agent.getName());
            List<LanguageTag> langs = agent.getPreferredLang().stream()
                    .sorted(Comparator.comparingDouble(LanguagePreference::getWeight).reversed())
                    .map(LanguagePreference::getLanguageTag)
                    .collect(Collectors.toList());
            b.setAiAgentLang(langs);
        }

        if (brandDTO.isAiOverridingEnabled()){
                AiOverridingDTO aiOverriding = brandDTO.getAiOverriding();
                b.setOverriddenDjName(aiOverriding.getName());
                b.setAdditionalUserInstruction(aiOverriding.getPrompt());
        }
        return b;
    }

    private boolean isSceneActive(String stationSlug, ZoneId zone, Scene scene, NavigableSet<Scene> allScenes, LocalTime currentTime, int currentDayOfWeek) {
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

    private LocalTime findNextSceneStartTime(String stationSlug, int currentDayOfWeek, Scene currentScene, NavigableSet<Scene> scenes) {
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

    public void addAiDj(String brand, String djName) {
        this.aiDjStatsRequestTracker.put(brand, new DjRequestInfo(LocalDateTime.now(), djName));
    }

    public Uni<AiDjStatsDTO> getAiDjStats(IStream stream) {
        return scriptService.getAllScriptsForBrandWithScenes(stream.getId(), SuperUser.build())
                .flatMap(scripts -> {
                    if (scripts.isEmpty()) {
                        return Uni.createFrom().item(() -> null);
                    }
                    ZonedDateTime now = ZonedDateTime.now(stream.getTimeZone());
                    LocalTime currentTime = now.toLocalTime();
                    int currentDayOfWeek = now.getDayOfWeek().getValue();

                    for (BrandScript brandScript : scripts) {
                        NavigableSet<Scene> scenes = brandScript.getScript().getScenes();
                        boolean useRelativeTiming = brandScript.getScript().getTimingMode() == SceneTimingMode.RELATIVE_TO_STREAM_START;
                        
                        Scene activeScene = null;
                        if (useRelativeTiming) {
                            activeScene = findActiveSceneByDuration(stream, scenes);
                        } else {
                            for (Scene scene : scenes) {
                                if (isSceneActive(stream.getSlugName(), stream.getTimeZone(), scene, scenes, currentTime, currentDayOfWeek)) {
                                    activeScene = scene;
                                    break;
                                }
                            }
                        }
                        
                        if (activeScene != null) {
                            final Scene scene = activeScene;
                            final LocalTime sceneStart = useRelativeTiming ? null : scene.getStartTime();
                            final LocalTime sceneEnd = useRelativeTiming ? null : findNextSceneStartTime(stream.getSlugName(), currentDayOfWeek, scene, scenes);
                            final int promptCount = scene.getPrompts() != null ?
                                (int) scene.getPrompts().stream().filter(LivePrompt::isActive).count() : 0;
                            final String nextSceneTitle = useRelativeTiming ? 
                                findNextSceneTitleByDuration(stream, scene, scenes) :
                                findNextSceneTitle(stream.getSlugName(), currentDayOfWeek, scene, scenes);
                            DjRequestInfo requestInfo = aiDjStatsRequestTracker.get(stream.getSlugName());
                            final LocalDateTime lastRequestTime;
                            final String trackedDjName;
                            if (requestInfo != null) {
                                lastRequestTime = requestInfo.requestTime();
                                trackedDjName = requestInfo.djName();
                            } else {
                                lastRequestTime = null;
                                trackedDjName = null;
                            }
                            final AiOverriding overriding = stream.getAiOverriding();
                            if (overriding != null) {
                                return Uni.createFrom().item(() -> new AiDjStatsDTO(
                                        scene.getId(),
                                        scene.getTitle(),
                                        sceneStart,
                                        sceneEnd,
                                        promptCount,
                                        nextSceneTitle,
                                        lastRequestTime,
                                        overriding.getName(),
                                        aiDjMessagesTracker.get(stream.getSlugName())
                                ));
                            } else {
                                return Uni.createFrom().item(() -> new AiDjStatsDTO(
                                        scene.getId(),
                                        scene.getTitle(),
                                        sceneStart,
                                        sceneEnd,
                                        promptCount,
                                        nextSceneTitle,
                                        lastRequestTime,
                                        trackedDjName,
                                        aiDjMessagesTracker.get(stream.getSlugName())
                                ));
                            }
                        }
                    }
                    return Uni.createFrom().item(() -> null);
                });
    }

    private String findNextSceneTitle(String stationSlug, int currentDayOfWeek, Scene currentScene, NavigableSet<Scene> scenes) {
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
        return !sortedScenes.isEmpty() ? sortedScenes.getFirst().getTitle() : null;
    }

    private Scene findActiveSceneByDuration(IStream station, NavigableSet<Scene> scenes) {
        LocalDateTime startTime = station.getStartTime();
        if (startTime == null) {
            LOGGER.warn("Station '{}': No start time set for relative timing mode", station.getSlugName());
            return null;
        }
        
        long elapsedSeconds = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now());
        
        long cumulativeDuration = 0;
        for (Scene scene : scenes) {
            int sceneDuration = scene.getDurationSeconds();
            if (elapsedSeconds < cumulativeDuration + sceneDuration) {
                return scene;
            }
            cumulativeDuration += sceneDuration;
        }
        
        return null;
    }

    private String findNextSceneTitleByDuration(IStream stream, Scene currentScene, NavigableSet<Scene> scenes) {
        LocalDateTime startTime = stream.getStartTime();
        if (startTime == null) {
            return null;
        }
        
        long elapsedSeconds = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now());
        
        long cumulativeDuration = 0;
        boolean foundCurrent = false;
        for (Scene scene : scenes) {
            if (foundCurrent) {
                return scene.getTitle();
            }
            int sceneDuration = scene.getDurationSeconds();
            if (elapsedSeconds < cumulativeDuration + sceneDuration && scene.getId().equals(currentScene.getId())) {
                foundCurrent = true;
            }
            cumulativeDuration += sceneDuration;
        }
        
        return null;
    }

    public void addMessage(String brandName, AiDjStatsDTO.MessageType type, String message) {
        aiDjMessagesTracker.computeIfAbsent(brandName, k -> new ArrayList<>())
                .add(new AiDjStatsDTO.StatusMessage(type, message));
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
                .map(genreId -> genreService.getById(genreId)
                        .map(genre -> genre.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                        .onFailure().recoverWithItem("Unknown"))
                .collect(Collectors.toList())).andFailFast()
                : Uni.createFrom().item(Collections.<String>emptyList());

        Uni<List<String>> labelsUni = (labelIds != null && !labelIds.isEmpty())
                ? Uni.join().all(labelIds.stream()
                .map(labelId -> labelService.getById(labelId)
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
