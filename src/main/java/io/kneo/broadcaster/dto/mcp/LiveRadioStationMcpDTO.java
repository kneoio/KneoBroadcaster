package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class LiveRadioStationMcpDTO {
    private String name;
    private RadioStationStatus radioStationStatus;
    private String djName;
    private TtsMcpDTO tts;
    private List<SongPromptMcpDTO> prompts;
}