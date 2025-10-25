package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.aihelper.BrandInfoDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.mcp.LivePromptDTO;
import io.kneo.broadcaster.dto.mcp.LiveRadioStationMcpDTO;
import io.kneo.broadcaster.dto.mcp.LiveRadioStationsMcpDTO;
import io.kneo.broadcaster.dto.mcp.TtsMcpDTO;
import io.kneo.broadcaster.service.AiHelperService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@ApplicationScoped
public class LiveRadioStationsMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveRadioStationsMCPTools.class);
    private static final List<RadioStationStatus> ACTIVE_STATUSES = List.of(
            RadioStationStatus.ON_LINE,
            RadioStationStatus.WARMING_UP,
            RadioStationStatus.QUEUE_SATURATED,
            RadioStationStatus.WAITING_FOR_CURATOR
    );

    @Inject
    AiHelperService aiHelperService;

    @Tool("get_live_radio_stations")
    @Description("Get live radio stations by status")
    public CompletableFuture<LiveRadioStationsMcpDTO> getLiveRadioStations() {
        return aiHelperService.getByStatus(ACTIVE_STATUSES)
                .chain(this::mapToLiveRadioStationsMcpDTO)
                .convert().toCompletableFuture();
    }

    private Uni<LiveRadioStationsMcpDTO> mapToLiveRadioStationsMcpDTO(List<BrandInfoDTO> brands) {
        if (brands == null || brands.isEmpty()) {
            LiveRadioStationsMcpDTO dto = new LiveRadioStationsMcpDTO();
            dto.setRadioStations(List.of());
            return Uni.createFrom().item(dto);
        }

        List<LiveRadioStationMcpDTO> stations = brands.stream()
                .map(this::mapToLiveRadioStationMcpDTO)
                .collect(Collectors.toList());

        LiveRadioStationsMcpDTO dto = new LiveRadioStationsMcpDTO();
        dto.setRadioStations(stations);
        return Uni.createFrom().item(dto);
    }

    private LiveRadioStationMcpDTO mapToLiveRadioStationMcpDTO(BrandInfoDTO brand) {
        LiveRadioStationMcpDTO dto = new LiveRadioStationMcpDTO();
        dto.setName(brand.getRadioStationName());

        if (brand.getAgent() != null) {
            dto.setDjName(brand.getAgent().getName());

            TtsMcpDTO tts = new TtsMcpDTO(
                    brand.getAgent().getPreferredVoice(),
                    brand.getAgent().getSecondaryVoice(),
                    brand.getAgent().getSecondaryVoiceName()
            );
            dto.setTts(tts);

            LivePromptDTO prompt = new LivePromptDTO(
                    brand.getAgent().getPrompt(),
                    null,
                    brand.getAgent().getLlmType(),
                    null
            );
            dto.setPrompt(prompt);
        }

        return dto;
    }
}
