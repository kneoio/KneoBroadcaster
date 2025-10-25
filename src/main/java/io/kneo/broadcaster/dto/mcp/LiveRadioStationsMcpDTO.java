package io.kneo.broadcaster.dto.mcp;

import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class LiveRadioStationsMcpDTO extends AbstractDTO {
    private List<LiveRadioStationMcpDTO> radioStations;
}