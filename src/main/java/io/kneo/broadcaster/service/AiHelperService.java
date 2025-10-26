package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ai.AiLiveAgentDTO;
import io.kneo.broadcaster.dto.aihelper.BrandInfoDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.mcp.LiveRadioStationMcpDTO;
import io.kneo.broadcaster.dto.mcp.LiveContainerMcpDTO;
import io.kneo.broadcaster.dto.mcp.LivePromptDTO;
import io.kneo.broadcaster.model.ai.Prompt;
import io.kneo.broadcaster.model.ai.SearchEngineType;
import io.kneo.broadcaster.model.cnst.AiAgentMode;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.radiostation.AiOverriding;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
            PromptService promptService
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
        this.scriptService = scriptService;
        this.promptService = promptService;
    }

    public Uni<LiveContainerMcpDTO> getOnline() {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> ACTIVE_STATUSES.contains(station.getStatus()))
                        .filter(station -> !station.getScheduler().isEnabled() || station.isAiControlAllowed())
                        .collect(Collectors.toList())
        ).flatMap(stations -> {
            List<Uni<LiveRadioStationMcpDTO>> stationUnis = stations.stream()
                    .map(this::buildLiveRadioStation)
                    .collect(Collectors.toList());

            return Uni.join().all(stationUnis).andFailFast()
                    .map(liveStations -> {
                        LiveContainerMcpDTO container = new LiveContainerMcpDTO();
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

        if (station.getAiAgentMode() == AiAgentMode.SCRIPT_FOLLOWING) {
            return fetchPromptForStation(station)
                    .map(prompt -> {
                        liveRadioStation.setPrompt(prompt);
                        return liveRadioStation;
                    });
        }

        return fetchPromptFromAgent(station)
                .map(prompt -> {
                    liveRadioStation.setPrompt(prompt);
                    return liveRadioStation;
                });
    }

    private Uni<LivePromptDTO> fetchPromptForStation(RadioStation station) {
        UUID agentId = station.getAiAgentId();
        if (agentId == null) {
            return Uni.createFrom().item(() -> null);
        }

        return Uni.combine().all()
                .unis(
                        promptService.getAll(100, 0, SuperUser.build()),
                        aiAgentService.getDTO(agentId, SuperUser.build(), LanguageCode.en)
                )
                .asTuple()
                .map(tuple -> {
                    List<io.kneo.broadcaster.dto.ai.PromptDTO> prompts = tuple.getItem1();
                    io.kneo.broadcaster.dto.ai.AiAgentDTO agent = tuple.getItem2();

                    if (prompts.isEmpty()) {
                        return null;
                    }

                    List<Prompt> enabledPrompts = prompts.stream()
                            .filter(p -> p.isEnabled())
                            .map(dto -> {
                                Prompt prompt = new Prompt();
                                prompt.setPrompt(dto.getPrompt());
                                prompt.setPromptType(dto.getPromptType());
                                prompt.setEnabled(dto.isEnabled());
                                return prompt;
                            })
                            .collect(Collectors.toList());

                    if (enabledPrompts.isEmpty()) {
                        return null;
                    }

                    Prompt selectedPrompt = enabledPrompts.get(new Random().nextInt(enabledPrompts.size()));
                    
                    io.kneo.broadcaster.model.ai.SearchEngineType searchEngineType = io.kneo.broadcaster.model.ai.SearchEngineType.PERPLEXITY;
                    if (agent.getSearchEngineType() != null) {
                        searchEngineType = io.kneo.broadcaster.model.ai.SearchEngineType.valueOf(agent.getSearchEngineType());
                    }
                    
                    return new LivePromptDTO(
                            selectedPrompt.getPrompt(),
                            selectedPrompt.getPromptType(),
                            io.kneo.broadcaster.model.ai.LlmType.valueOf(agent.getLlmType()),
                            searchEngineType
                    );
                });
    }

    private Uni<LivePromptDTO> fetchPromptFromAgent(RadioStation station) {
        UUID agentId = station.getAiAgentId();
        if (agentId == null) {
            return Uni.createFrom().item(() -> null);
        }

        return aiAgentService.getDTO(agentId, SuperUser.build(), LanguageCode.en)
                .map(agent -> {
                    List<io.kneo.broadcaster.dto.ai.PromptDTO> prompts = agent.getPrompts();
                    if (prompts == null || prompts.isEmpty()) {
                        return null;
                    }

                    List<io.kneo.broadcaster.dto.ai.PromptDTO> enabledPrompts = prompts.stream()
                            .filter(p -> p.isEnabled())
                            .toList();

                    if (enabledPrompts.isEmpty()) {
                        return null;
                    }

                    io.kneo.broadcaster.dto.ai.PromptDTO selectedPrompt = enabledPrompts.get(new Random().nextInt(enabledPrompts.size()));
                    
                    io.kneo.broadcaster.model.ai.SearchEngineType searchEngineType = io.kneo.broadcaster.model.ai.SearchEngineType.PERPLEXITY;
                    if (agent.getSearchEngineType() != null) {
                        searchEngineType = io.kneo.broadcaster.model.ai.SearchEngineType.valueOf(agent.getSearchEngineType());
                    }
                    
                    return new LivePromptDTO(
                            selectedPrompt.getPrompt(),
                            selectedPrompt.getPromptType(),
                            io.kneo.broadcaster.model.ai.LlmType.valueOf(agent.getLlmType()),
                            searchEngineType
                    );
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

                    List<io.kneo.broadcaster.dto.ai.PromptDTO> prompts = agent.getPrompts();
                    List<String> msgPrompts = agent.getMessagePrompts();
                    List<String> podcastPrompts = agent.getMiniPodcastPrompts();

                    if (prompts == null || prompts.isEmpty()) {
                        return Uni.createFrom().item(brand);
                    }

                    List<io.kneo.broadcaster.dto.ai.PromptDTO> enabledPrompts = prompts.stream()
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