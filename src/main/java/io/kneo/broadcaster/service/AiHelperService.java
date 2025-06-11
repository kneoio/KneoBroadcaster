package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.ai.LiveAgentDTO;
import io.kneo.broadcaster.dto.aihelper.BrandInfoDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    public Uni<List<BrandInfoDTO>> getByStatus(List<RadioStationStatus> statuses) {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> statuses.contains(station.getStatus()))
                        .map(station -> {
                            BrandInfoDTO brand = new BrandInfoDTO();
                            brand.setRadioStationName(station.getSlugName());
                            brand.setRadioStationStatus(station.getStatus());
                            if (station.getAiAgent() != null) {
                                AiAgent aiAgent = station.getAiAgent();
                                LiveAgentDTO liveAgentDTO = new LiveAgentDTO();
                                liveAgentDTO.setName(aiAgent.getName());
                                liveAgentDTO.setMainPrompt(aiAgent.getMainPrompt());
                                if (aiAgent.getPreferredVoice() != null) {
                                    liveAgentDTO.setPreferredVoice(aiAgent.getPreferredVoice().get(0).getId());
                                }
                                brand.setAgent(liveAgentDTO);
                            }
                            return brand;
                        })
                        .collect(Collectors.toList())
        );
    }

}