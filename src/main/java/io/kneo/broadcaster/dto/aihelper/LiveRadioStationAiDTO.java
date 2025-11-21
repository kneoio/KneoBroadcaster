package io.kneo.broadcaster.dto.aihelper;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.mcp.SongPromptMcpDTO;
import io.kneo.broadcaster.dto.mcp.TtsMcpDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class LiveRadioStationAiDTO {
    private String name;
    private String slugName;
    private RadioStationStatus radioStationStatus;
    private String djName;
    private TtsMcpDTO tts;
    private List<SongPromptMcpDTO> prompts;
    private String info;
}