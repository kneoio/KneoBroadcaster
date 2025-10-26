package io.kneo.broadcaster.dto.mcp;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class LiveContainerMcpDTO {
    private List<LiveRadioStationMcpDTO> radioStations;
}