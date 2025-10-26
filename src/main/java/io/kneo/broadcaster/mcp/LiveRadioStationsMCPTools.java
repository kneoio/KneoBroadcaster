package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.mcp.LiveContainerMcpDTO;
import io.kneo.broadcaster.service.AiHelperService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class LiveRadioStationsMCPTools {

    @Inject
    AiHelperService aiHelperService;

    @Tool("get_live_radio_stations")
    @Description("Get live radio stations by status")
    public CompletableFuture<LiveContainerMcpDTO> getLiveRadioStations() {
        return aiHelperService.getOnline()
                .convert().toCompletableFuture();
    }
}
