package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ai.LiveAgentDTO;
import io.kneo.broadcaster.dto.aihelper.BrandInfoDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Random;
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
                        .filter(station -> !station.getSchedule().isEnabled() || station.isAiControlAllowed())
                        .collect(Collectors.toList())
        ).chain(stations -> {
            if (stations.isEmpty()) {
                return Uni.createFrom().item(List.of());
            }

            List<Uni<BrandInfoDTO>> brandUnis = stations.stream()
                    .map(station -> {
                        BrandInfoDTO brand = new BrandInfoDTO();
                        brand.setRadioStationName(station.getSlugName());
                        brand.setRadioStationStatus(station.getStatus());

                        if (station.getAiAgentId() != null) {
                            return aiAgentService.getById(station.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                    .map(agent -> {
                                        LiveAgentDTO liveAgentDTO = new LiveAgentDTO();
                                        liveAgentDTO.setName(agent.getName());
                                        List<String> prompts = agent.getPrompts();
                                        Random random = new Random();
                                        String randomPrompt = prompts.get(random.nextInt(prompts.size()));
                                        liveAgentDTO.setPrompt(randomPrompt);
                                        liveAgentDTO.setFillers(agent.getFillerPrompt());
                                        if (agent.getPreferredVoice() != null && !agent.getPreferredVoice().isEmpty()) {
                                            liveAgentDTO.setPreferredVoice(agent.getPreferredVoice().get(0).getId());
                                        }
                                        liveAgentDTO.setTalkativity(agent.getTalkativity());
                                        brand.setAgent(liveAgentDTO);
                                        return brand;
                                    });
                        } else {
                            return Uni.createFrom().item(brand);
                        }
                    })
                    .collect(Collectors.toList());

            return Uni.join().all(brandUnis).andFailFast();
        });
    }
}