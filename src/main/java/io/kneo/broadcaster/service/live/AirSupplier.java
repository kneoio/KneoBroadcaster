package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.LiveContainerDTO;
import io.kneo.broadcaster.dto.aihelper.LiveRadioStationDTO;
import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.aihelper.TtsDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStatsDTO;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.RadioStream;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.stream.HlsSegment;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.util.AiHelperUtils;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@ApplicationScoped
public class AirSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(AirSupplier.class);

    private final RadioStationPool radioStationPool;
    private final AiAgentService aiAgentService;
    private final OneTimeStreamSupplier oneTimeStreamSupplier;
    private final RadioStreamSupplier radioStreamSupplier;

    private final Map<String, List<AiDjStatsDTO.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();

    @Inject
    public AirSupplier(
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService,
            OneTimeStreamSupplier oneTimeStreamSupplier,
            RadioStreamSupplier radioStreamSupplier
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.oneTimeStreamSupplier = oneTimeStreamSupplier;
        this.radioStreamSupplier = radioStreamSupplier;
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
            addMessage(stream.getSlugName(), AiDjStatsDTO.MessageType.INFO,
                    String.format("The playlist is saturated (size %s, duration %.1f min)", queueSize, queuedDurationInMinutes));

            return Uni.createFrom().item(() -> null);
        } else {
            liveRadioStation.setRadioStationStatus(stream.getStatus());
        }

        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .flatMap(agent -> {
                    LanguageCode broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                    liveRadioStation.setSlugName(stream.getSlugName());
                    liveRadioStation.setName(stream.getLocalizedName().get(broadcastingLanguage));
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


                    if (stream instanceof OneTimeStream oneTimeStream) {
                        return oneTimeStreamSupplier.fetchPromptForOneTimeStream(
                                        oneTimeStream,
                                        agent,
                                        broadcastingLanguage,
                                        additionalInstruction,
                                        this::addMessage
                                )
                                .flatMap(tuple -> {
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
                    }

                    if (stream instanceof RadioStream radioStream) {
                        return radioStreamSupplier.fetchStuffForRadioStream(
                                        radioStream,
                                        agent,
                                        broadcastingLanguage,
                                        additionalInstruction,
                                        this::addMessage
                                )
                                .flatMap(tuple -> {
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
                    }

                    return Uni.createFrom().failure(new IllegalStateException(
                            "Unsupported stream type: " + stream.getClass().getSimpleName()));
                });
    }

    private void clearDashboardMessages(String stationSlug) {
        aiDjMessagesTracker.remove(stationSlug);
    }

    private void addMessage(String stationSlug, AiDjStatsDTO.MessageType type, String message) {
        aiDjMessagesTracker.computeIfAbsent(stationSlug, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new AiDjStatsDTO.StatusMessage(type, message));
    }
}
