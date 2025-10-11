package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ai.AiLiveAgentDTO;
import io.kneo.broadcaster.dto.aihelper.BrandInfoDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.radiostation.AiOverriding;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {

    private final RadioStationPool radioStationPool;
    private final AiAgentService aiAgentService;

    @Inject
    public AiHelperService(
            RadioStationPool radioStationPool,
            AiAgentService aiAgentService
    ) {
        this.radioStationPool = radioStationPool;
        this.aiAgentService = aiAgentService;
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

                return aiAgentService.getById(agentId, SuperUser.build(), LanguageCode.en).flatMap(agent -> {
                    AiLiveAgentDTO dto = new AiLiveAgentDTO();
                    dto.setName(agent.getName());
                    dto.setLlmType(agent.getLlmType());
                    dto.setPreferredLang(agent.getPreferredLang());

                    List<String> prompts = agent.getPrompts();
                    List<String> msgPrompts = agent.getMessagePrompts();
                    List<String> podcastPrompts = agent.getMiniPodcastPrompts();

                    if (prompts == null || prompts.isEmpty()) {
                        return Uni.createFrom().item(brand);
                    }

                    String randomPrompt = prompts.get(new Random().nextInt(prompts.size()));

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