package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class LiveRadioStationMcpDTO {
    private String name;
    private RadioStationStatus radioStationStatus;
    private String djName;
    private TtsMcpDTO tts;
    private LivePromptMcpDTO prompt;
}