package io.kneo.broadcaster.dto.mcp;

import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class LiveRadioStationMcpDTO extends AbstractDTO {
    private String name;
    private String djName;
    private TtsMcpDTO tts;
    private LivePromptDTO prompt;
}